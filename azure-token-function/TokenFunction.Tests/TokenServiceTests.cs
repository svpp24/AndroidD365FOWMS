// dotnet test (from azure-token-function). Entra ID and D365FO are fakes.
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace HsMobileOps.TokenFunction.Tests;

/// <summary>What DeviceKey does on Android: EC P-256, SHA256withECDSA, DER signature, base64.</summary>
internal sealed class Device(string id)
{
    private readonly ECDsa _key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    public string Id { get; } = id;
    public string Public => Convert.ToBase64String(_key.ExportSubjectPublicKeyInfo());

    public string Sign(string text) => Convert.ToBase64String(
        _key.SignData(Encoding.UTF8.GetBytes(text), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));

    public JsonObject RegisterBody(string code) => new()
    {
        ["code"] = code, ["deviceId"] = Id, ["publicKey"] = Public,
        ["signature"] = Sign(Protocol.RegisterText(code, Id, Public)),
        ["deviceBrand"] = "Zebra", ["deviceModel"] = "TC22", ["platform"] = "Android", ["platformVersion"] = "14",
        ["appVersionName"] = "1.5.8", ["d365Url"] = Fixture.D365 + "/", ["company"] = "BSP",
    };

    public JsonObject BackupBody(string items, string backupId = "", string nonce = "b1", long timestamp = Fixture.Now)
    {
        var id = backupId.Length == 0 ? Id : backupId;
        return new()
        {
            ["deviceId"] = Id, ["resource"] = Fixture.D365, ["timestamp"] = timestamp, ["nonce"] = nonce,
            ["backupDeviceId"] = id, ["dataAreaId"] = "bsp", ["userName"] = "002", ["itemsJson"] = items,
            ["signature"] = Sign(Protocol.BackupText(Id, Fixture.D365, timestamp, nonce, id, "bsp", items)),
        };
    }

    public JsonObject TokenBody(string nonce = "n1", long timestamp = Fixture.Now) => new()
    {
        ["deviceId"] = Id, ["resource"] = Fixture.D365, ["timestamp"] = timestamp, ["nonce"] = nonce,
        ["signature"] = Sign(Protocol.TokenText(Id, Fixture.D365, timestamp, nonce)),
    };
}

/// <summary>Entra ID (client credentials) and the D365FO setup service, as far as the service uses them.</summary>
internal sealed class FakeCloud : HttpMessageHandler
{
    public Dictionary<string, int> Codes { get; } = new() { ["GOODCODE"] = 1 };
    public Dictionary<string, (string PublicKey, bool IsActive)> Devices { get; } = new();
    public List<string> AadCalls { get; } = [];
    public List<string> D365Calls { get; } = [];
    public HashSet<string> ThrottledApps { get; set; } = [];
    public List<JsonObject> SavedWork { get; } = [];

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var url = request.RequestUri!.ToString();
        var body = await request.Content!.ReadAsStringAsync(ct);
        if (url.Contains("login.microsoftonline.com"))
        {
            var form = body.Split('&').Select(p => p.Split('=')).ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1].Replace('+', ' ')));
            var app = form["client_id"];
            AadCalls.Add(app);
            // No secret: Entra ID takes the managed identity's token as the credential.
            if (!form.ContainsKey("client_secret"))
            {
                Assert.Equal("mi-assertion", form["client_assertion"]);
                Assert.Equal(Protocol.AssertionType, form["client_assertion_type"]);
            }
            if (ThrottledApps.Contains(app))
            {
                var throttled = Json(HttpStatusCode.TooManyRequests, new JsonObject());
                throttled.Headers.Add("Retry-After", "20");
                return throttled;
            }
            return Json(HttpStatusCode.OK, new JsonObject { ["access_token"] = "tok-" + app, ["expires_in"] = 3600 });
        }
        Assert.StartsWith("tok-", request.Headers.Authorization!.Parameter);
        var op = url[(url.LastIndexOf('/') + 1)..];
        var req = JsonNode.Parse(body)!["_request"]!.AsObject();
        D365Calls.Add(op);
        if (op == "syncWork")
        {
            Assert.Contains("/HSMobileOpsDeviceWorkSvc/", url);
            SavedWork.Add(req);
            var items = JsonNode.Parse(req["ItemsJson"]!.GetValue<string>())!.AsArray().Count;
            return Json(HttpStatusCode.OK, new JsonObject { ["Success"] = true, ["Stored"] = items });
        }
        if (op == "registerDeviceKey")
        {
            var code = req["Code"]!.GetValue<string>();
            if (Codes.GetValueOrDefault(code) < 1)
                return Json(HttpStatusCode.OK, new JsonObject { ["Success"] = false, ["ErrorCode"] = "CODE_USED_UP", ["Message"] = "used" });
            Codes[code]--;
            Devices[req["DeviceId"]!.GetValue<string>()] = (req["PublicKey"]!.GetValue<string>(), true);
            return Json(HttpStatusCode.OK, new JsonObject
            {
                ["Success"] = true, ["Company"] = "BSP", ["DeviceName"] = "Zebra TC22",
                ["ActiveDevices"] = 7, ["MaxDevices"] = 100, ["Message"] = "Device ready - 7 of 100 devices.",
            });
        }
        if (!Devices.TryGetValue(req["DeviceId"]!.GetValue<string>(), out var device))
            return Json(HttpStatusCode.OK, new JsonObject { ["Success"] = false, ["ErrorCode"] = "DEVICE_UNKNOWN" });
        return Json(HttpStatusCode.OK, new JsonObject
        {
            ["Success"] = true, ["PublicKey"] = device.PublicKey, ["IsActive"] = device.IsActive,
            ["LicenseValid"] = true, ["ErrorCode"] = device.IsActive ? "" : "DEVICE_INACTIVE",
        });
    }

    private static HttpResponseMessage Json(HttpStatusCode status, JsonObject body) =>
        new(status) { Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json") };
}

internal sealed class MemoryNonces : INonceStore
{
    private readonly HashSet<(string, string)> _used = [];
    public Task<bool> UseAsync(string deviceId, string nonce, long timestamp, CancellationToken ct) =>
        Task.FromResult(_used.Add((deviceId, nonce)));
}

internal sealed class FakeIdentity : IAssertionSource
{
    public int Calls { get; private set; }
    public Task<string> GetAssertionAsync(CancellationToken ct)
    {
        Calls++;
        return Task.FromResult("mi-assertion");
    }
}

internal static class Fixture
{
    public const string D365 = "https://contoso.operations.dynamics.com";
    public const long Now = 1_800_000_000;

    public static TokenService Service(FakeCloud cloud, int apps = 2, Func<DateTimeOffset>? clock = null,
        string? registrations = null, FakeIdentity? identity = null) =>
        new(Config.FromSettings("tenant-1", D365 + "/",
                registrations ?? "[" + string.Join(",", Enumerable.Range(0, apps).Select(i => $"\"app{i}\"")) + "]"),
            new HttpClient(cloud), new MemoryNonces(), identity ?? new FakeIdentity(), NullLogger.Instance,
            clock ?? (() => DateTimeOffset.FromUnixTimeSeconds(Now)));
}

public class TokenServiceTests
{
    [Fact]
    public async Task Setup_then_token()
    {
        var (cloud, device) = (new FakeCloud(), new Device("dev-1"));
        var svc = Fixture.Service(cloud);
        var r = await svc.RegisterAsync(device.RegisterBody("goodcode"));
        Assert.Equal(200, r.HttpStatus);
        Assert.True(r.Body["success"]!.GetValue<bool>());
        Assert.Equal("BSP", r.Body["company"]!.GetValue<string>());
        Assert.Equal(7, r.Body["activeDevices"]!.GetValue<long>());
        Assert.Equal(100, r.Body["maxDevices"]!.GetValue<long>());
        r = await svc.TokenAsync(device.TokenBody());
        Assert.Equal(200, r.HttpStatus);
        Assert.StartsWith("tok-app", r.Body["access_token"]!.GetValue<string>());
        Assert.Equal(3600, r.Body["expires_in"]!.GetValue<long>());
    }

    [Fact]
    public async Task Refusals_from_d365fo_pass_through()
    {
        var (cloud, a, b) = (new FakeCloud(), new Device("a"), new Device("b"));
        var svc = Fixture.Service(cloud);
        await svc.RegisterAsync(a.RegisterBody("GOODCODE"));
        var r = await svc.RegisterAsync(b.RegisterBody("GOODCODE"));
        Assert.Equal((200, false, "CODE_USED_UP"),
            (r.HttpStatus, r.Body["success"]!.GetValue<bool>(), r.Body["errorCode"]!.GetValue<string>()));
    }

    [Fact]
    public async Task Register_needs_the_key_holder_and_a_known_environment()
    {
        var (cloud, device, other) = (new FakeCloud(), new Device("dev-1"), new Device("x"));
        var body = device.RegisterBody("GOODCODE");
        body["signature"] = other.Sign(Protocol.RegisterText("GOODCODE", "dev-1", device.Public));
        Assert.Equal("BAD_SIGNATURE", (await Fixture.Service(cloud).RegisterAsync(body)).Body["errorCode"]!.GetValue<string>());
        body = device.RegisterBody("GOODCODE");
        body["d365Url"] = "https://other.operations.dynamics.com";
        Assert.Equal("UNKNOWN_ENVIRONMENT", (await Fixture.Service(cloud).RegisterAsync(body)).Body["errorCode"]!.GetValue<string>());
        Assert.Empty(cloud.D365Calls);
    }

    [Fact]
    public async Task Token_refusals()
    {
        var (cloud, device, stranger) = (new FakeCloud(), new Device("dev-1"), new Device("dev-2"));
        var svc = Fixture.Service(cloud);
        await svc.RegisterAsync(device.RegisterBody("GOODCODE"));
        var r = await svc.TokenAsync(stranger.TokenBody());
        Assert.Equal((403, "DEVICE_UNKNOWN"), (r.HttpStatus, r.Body["errorCode"]!.GetValue<string>()));
        Assert.Equal("CLOCK_SKEW", (await svc.TokenAsync(device.TokenBody(timestamp: Fixture.Now - 900))).Body["errorCode"]!.GetValue<string>());
        var forged = device.TokenBody();
        forged["signature"] = stranger.Sign(Protocol.TokenText("dev-1", Fixture.D365, Fixture.Now, "n1"));
        Assert.Equal("BAD_SIGNATURE", (await svc.TokenAsync(forged)).Body["errorCode"]!.GetValue<string>());
        Assert.Equal(200, (await svc.TokenAsync(device.TokenBody("n9"))).HttpStatus);
        Assert.Equal("REPLAY", (await svc.TokenAsync(device.TokenBody("n9"))).Body["errorCode"]!.GetValue<string>());
        Assert.Equal("INVALID_REQUEST", (await svc.TokenAsync(new JsonObject())).Body["errorCode"]!.GetValue<string>());
    }

    [Fact]
    public async Task A_deactivated_device_is_refused_once_the_key_cache_expires()
    {
        var (cloud, device) = (new FakeCloud(), new Device("dev-1"));
        var now = Fixture.Now;
        var svc = Fixture.Service(cloud, clock: () => DateTimeOffset.FromUnixTimeSeconds(now));
        await svc.RegisterAsync(device.RegisterBody("GOODCODE"));
        Assert.Equal(200, (await svc.TokenAsync(device.TokenBody("a"))).HttpStatus);
        cloud.Devices["dev-1"] = (cloud.Devices["dev-1"].PublicKey, false);
        now += Protocol.DeviceKeyCacheSeconds + 1;
        var r = await svc.TokenAsync(device.TokenBody("b", now));
        Assert.Equal((403, "DEVICE_INACTIVE"), (r.HttpStatus, r.Body["errorCode"]!.GetValue<string>()));
    }

    [Fact]
    public async Task Tokens_are_cached_and_a_throttled_registration_is_skipped()
    {
        var cloud = new FakeCloud();
        var svc = Fixture.Service(cloud, apps: 3);
        var devices = Enumerable.Range(0, 6).Select(i => new Device($"d{i}")).ToList();
        cloud.Codes["GOODCODE"] = 6;
        foreach (var d in devices) await svc.RegisterAsync(d.RegisterBody("GOODCODE"));
        var calls = cloud.AadCalls.Count;
        for (var i = 0; i < devices.Count; i++)
            Assert.Equal(200, (await svc.TokenAsync(devices[i].TokenBody($"t{i}"))).HttpStatus);
        Assert.Equal(calls, cloud.AadCalls.Count); // app tokens come from the cache
        cloud.ThrottledApps = ["app0", "app1"];
        svc.ClearTokenCache();
        for (var i = 0; i < devices.Count; i++)
            Assert.Equal("tok-app2", (await svc.TokenAsync(devices[i].TokenBody($"u{i}"))).Body["access_token"]!.GetValue<string>());
        cloud.ThrottledApps = ["app0", "app1", "app2"];
        svc.ClearTokenCache();
        var r = await svc.TokenAsync(devices[0].TokenBody("v"));
        Assert.Equal((429, "20"), (r.HttpStatus, r.Headers!["Retry-After"]));
    }

    [Fact]
    public async Task No_secret_the_managed_identity_vouches_for_each_registration()
    {
        var (cloud, identity) = (new FakeCloud(), new FakeIdentity());
        var svc = Fixture.Service(cloud, apps: 1, identity: identity);
        var device = new Device("dev-1");
        await svc.RegisterAsync(device.RegisterBody("GOODCODE"));
        Assert.Equal(200, (await svc.TokenAsync(device.TokenBody())).HttpStatus);
        Assert.Equal(1, identity.Calls); // one app token, cached afterwards; FakeCloud checked the assertion
    }

    [Fact]
    public async Task A_registration_with_a_secret_still_works()
    {
        var (cloud, device, identity) = (new FakeCloud(), new Device("dev-1"), new FakeIdentity());
        var svc = Fixture.Service(cloud, registrations: """[{"id": "app0", "secret": "s0"}]""", identity: identity);
        await svc.RegisterAsync(device.RegisterBody("GOODCODE"));
        Assert.Equal(200, (await svc.TokenAsync(device.TokenBody())).HttpStatus);
        Assert.Equal(0, identity.Calls);
    }

    [Fact]
    public async Task A_deactivated_device_can_still_save_its_last_work()
    {
        var (cloud, device) = (new FakeCloud(), new Device("dev-1"));
        var svc = Fixture.Service(cloud);
        await svc.RegisterAsync(device.RegisterBody("GOODCODE"));
        cloud.Devices["dev-1"] = (cloud.Devices["dev-1"].PublicKey, false);
        Assert.Equal((403, "DEVICE_INACTIVE"), (await svc.TokenAsync(device.TokenBody())) is var t ? (t.HttpStatus, t.Body["errorCode"]!.GetValue<string>()) : default);

        var r = await svc.BackupAsync(device.BackupBody("""[{"Key":"a"},{"Key":"b"}]""", "dev-1~2"));
        Assert.Equal((200, true, 2L), (r.HttpStatus, r.Body["success"]!.GetValue<bool>(), r.Body["stored"]!.GetValue<long>()));
        var saved = Assert.Single(cloud.SavedWork);
        Assert.Equal("dev-1~2", saved["DeviceId"]!.GetValue<string>());
        Assert.False(saved["Complete"]!.GetValue<bool>()); // adds or updates, never deletes
        Assert.Equal("REPLAY", (await svc.BackupAsync(device.BackupBody("""[{"Key":"a"}]""", "dev-1~2"))).Body["errorCode"]!.GetValue<string>());
    }

    [Fact]
    public async Task A_backup_needs_the_device_key_and_its_own_backup_id()
    {
        var (cloud, device, other) = (new FakeCloud(), new Device("dev-1"), new Device("dev-2"));
        var svc = Fixture.Service(cloud);
        await svc.RegisterAsync(device.RegisterBody("GOODCODE"));

        var forged = device.BackupBody("[]");
        forged["signature"] = other.Sign(Protocol.BackupText("dev-1", Fixture.D365, Fixture.Now, "b1", "dev-1", "bsp", "[]"));
        Assert.Equal("BAD_SIGNATURE", (await svc.BackupAsync(forged)).Body["errorCode"]!.GetValue<string>());

        var swapped = device.BackupBody("""[{"Key":"a"}]""", nonce: "b2");
        swapped["itemsJson"] = """[{"Key":"evil"}]"""; // the signature covers the work itself
        Assert.Equal("BAD_SIGNATURE", (await svc.BackupAsync(swapped)).Body["errorCode"]!.GetValue<string>());

        Assert.Equal("INVALID_REQUEST", (await svc.BackupAsync(device.BackupBody("[]", "dev-2", "b3"))).Body["errorCode"]!.GetValue<string>());
        Assert.Equal("DEVICE_UNKNOWN", (await svc.BackupAsync(other.BackupBody("[]"))).Body["errorCode"]!.GetValue<string>());
        Assert.Equal("CLOCK_SKEW", (await svc.BackupAsync(device.BackupBody("[]", nonce: "b4", timestamp: Fixture.Now - 900))).Body["errorCode"]!.GetValue<string>());
        Assert.Empty(cloud.SavedWork);
    }

    [Fact]
    public void Config_needs_everything()
    {
        Assert.Throws<ArgumentException>(() => Config.FromSettings("", Fixture.D365, """["a"]"""));
        Assert.Throws<ArgumentException>(() => Config.FromSettings("t", Fixture.D365, "[]"));
    }

    [Fact]
    public void Only_p256_der_signatures_verify()
    {
        var device = new Device("d");
        Assert.True(Protocol.VerifyDeviceSignature(device.Public, "x", device.Sign("x")));
        Assert.False(Protocol.VerifyDeviceSignature(device.Public, "y", device.Sign("x")));
        Assert.False(Protocol.VerifyDeviceSignature("not base64!", "x", device.Sign("x")));
        using var p384 = ECDsa.Create(ECCurve.NamedCurves.nistP384);
        var sig = Convert.ToBase64String(p384.SignData("x"u8.ToArray(), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
        Assert.False(Protocol.VerifyDeviceSignature(Convert.ToBase64String(p384.ExportSubjectPublicKeyInfo()), "x", sig));
    }

    [Fact]
    public void Nonce_buckets_sort_as_text()
    {
        Assert.Equal("000003000000", TableNonces.Bucket(Fixture.Now));
        Assert.True(string.CompareOrdinal(TableNonces.Bucket(599), TableNonces.Bucket(600)) < 0);
    }
}
