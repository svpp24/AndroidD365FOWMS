// The token service's logic - no Azure Functions types, so it is testable alone.
// Contract: ../../docs/device-setup.md.
//
// A handheld set up with a device setup code holds no client secret. It holds an EC P-256 key in
// its Keystore; D365FO holds the public key (HSMobileOpsDeviceSetupSvc). This service holds the
// customer's app registrations and hands out D365FO access tokens to devices that prove they hold
// their key and that D365FO still lists as active.
//
// No secrets: each app registration trusts this Function App's managed identity (a federated
// credential). The function gets its identity's token for api://AzureADTokenExchange and hands it
// to Entra ID in place of a client secret; the D365FO token that comes back is the app
// registration's own, so D365FO (Microsoft Entra applications) accepts it as before.
using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;

namespace HsMobileOps.TokenFunction;

public sealed record AppRegistration(string ClientId, string Secret = "");

public sealed record Config(string TenantId, IReadOnlyList<string> D365Urls, IReadOnlyList<AppRegistration> Apps)
{
    /// <summary>
    /// From the app settings. APP_REGISTRATIONS is ["client-id", ...] - or [{"id","secret"}] from an
    /// older setup, which still works.
    /// </summary>
    public static Config FromSettings(string? tenantId, string? d365Urls, string? appsJson)
    {
        var apps = new List<AppRegistration>();
        using (var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(appsJson) ? "[]" : appsJson))
        {
            foreach (var a in doc.RootElement.EnumerateArray())
            {
                if (a.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(a.GetString()))
                    apps.Add(new AppRegistration(a.GetString()!.Trim()));
                else if (a.ValueKind == JsonValueKind.Object && a.TryGetProperty("id", out var id) && !string.IsNullOrWhiteSpace(id.GetString()))
                    apps.Add(new AppRegistration(id.GetString()!.Trim(),
                        a.TryGetProperty("secret", out var s) ? (s.GetString() ?? "").Trim() : ""));
            }
        }
        var urls = (d365Urls ?? "").Split(',').Where(u => !string.IsNullOrWhiteSpace(u)).Select(Protocol.Normalize).ToList();
        if (string.IsNullOrWhiteSpace(tenantId) || urls.Count == 0 || apps.Count == 0)
            throw new ArgumentException("TENANT_ID, D365_URLS and APP_REGISTRATIONS are all required");
        return new Config(tenantId.Trim(), urls, apps);
    }
}

public sealed record Result(int HttpStatus, JsonObject Body, IReadOnlyDictionary<string, string>? Headers = null);

/// <summary>Replay protection: false when this nonce was used already.</summary>
public interface INonceStore
{
    Task<bool> UseAsync(string deviceId, string nonce, long timestamp, CancellationToken ct);
}

/// <summary>This Function App's own identity token for api://AzureADTokenExchange.</summary>
public interface IAssertionSource
{
    Task<string> GetAssertionAsync(CancellationToken ct);
}

public sealed class ThrottledException(int retryAfter) : Exception($"throttled, retry after {retryAfter}s")
{
    public int RetryAfter { get; } = retryAfter;
}

public sealed class UnavailableException(string message) : Exception(message);

public static class Protocol
{
    public const int ClockSkewSeconds = 300;
    public const int DeviceKeyCacheSeconds = 300;
    public const string TokenExchangeAudience = "api://AzureADTokenExchange";
    public const string AssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    public const string ServicePath = "/api/services/HSMobileOpsServiceGroup/HSMobileOpsDeviceSetupSvc/";

    public static string Normalize(string url) => url.Trim().TrimEnd('/').ToLowerInvariant();

    public static string RegisterText(string code, string deviceId, string publicKey) =>
        $"HSMODEV1-REGISTER\n{code.Trim().ToUpperInvariant()}\n{deviceId}\n{publicKey}";

    public static string TokenText(string deviceId, string resource, long timestamp, string nonce) =>
        $"HSMODEV1-TOKEN\n{deviceId}\n{resource}\n{timestamp}\n{nonce}";

    /// <summary>SHA256withECDSA, DER signature, P-256 - what Android's Keystore makes (DeviceKey.sign).</summary>
    public static bool VerifyDeviceSignature(string publicKeyB64, string text, string signatureB64)
    {
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKeyB64), out _);
            if (key.ExportParameters(false).Curve.Oid.Value != "1.2.840.10045.3.1.7") return false; // P-256 only
            return key.VerifyData(Encoding.UTF8.GetBytes(text), Convert.FromBase64String(signatureB64),
                HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (Exception e) when (e is FormatException or CryptographicException or ArgumentException)
        {
            return false;
        }
    }
}

public sealed class TokenService(
    Config config,
    HttpClient http,
    INonceStore nonces,
    IAssertionSource? identity,
    ILogger logger,
    Func<DateTimeOffset>? clock = null)
{
    private readonly Func<DateTimeOffset> _clock = clock ?? (() => DateTimeOffset.UtcNow);
    private readonly ConcurrentDictionary<(int, string), (string Token, DateTimeOffset Expires)> _tokens = new();
    private readonly ConcurrentDictionary<(string, string), (JsonObject Answer, DateTimeOffset Expires)> _keys = new();

    /// <summary>Test hook: forget the cached app tokens.</summary>
    internal void ClearTokenCache() => _tokens.Clear();

    // -- app registrations -----------------------------------------------------------------

    /// <summary>Each device keeps to one registration, spreading D365FO's per-registration limits.</summary>
    private int AppIndex(string deviceId, int shift = 0)
    {
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(deviceId));
        var n = (uint)(digest[0] << 24 | digest[1] << 16 | digest[2] << 8 | digest[3]);
        return (int)((n + (uint)shift) % (uint)config.Apps.Count);
    }

    /// <summary>A client-credentials token of registration [index], cached until 5 minutes before it expires.</summary>
    private async Task<(string Token, DateTimeOffset Expires)> AppTokenAsync(int index, string resource, CancellationToken ct)
    {
        if (_tokens.TryGetValue((index, resource), out var cached) && cached.Expires - TimeSpan.FromMinutes(5) > _clock())
            return cached;
        var app = config.Apps[index];
        var form = new Dictionary<string, string>
        {
            ["grant_type"] = "client_credentials",
            ["client_id"] = app.ClientId,
            ["scope"] = resource + "/.default",
        };
        if (app.Secret.Length > 0)
        {
            form["client_secret"] = app.Secret;
        }
        else
        {
            if (identity is null) throw new UnavailableException("no managed identity and no secret for this registration");
            form["client_assertion_type"] = Protocol.AssertionType;
            form["client_assertion"] = await identity.GetAssertionAsync(ct);
        }
        using var response = await http.PostAsync(
            $"https://login.microsoftonline.com/{config.TenantId}/oauth2/v2.0/token", new FormUrlEncodedContent(form), ct);
        var raw = await response.Content.ReadAsStringAsync(ct);
        if (response.StatusCode == HttpStatusCode.TooManyRequests) throw new ThrottledException(RetryAfter(response));
        if (response.StatusCode != HttpStatusCode.OK)
        {
            logger.LogError("Entra ID refused registration {ClientId}: {Status} {Body}", app.ClientId, (int)response.StatusCode, Clip(raw));
            throw new UnavailableException($"Entra ID answered {(int)response.StatusCode}");
        }
        var data = JsonNode.Parse(raw)!.AsObject();
        var expiresIn = data["expires_in"] is { } e ? int.Parse(e.ToString()) : 3600;
        var token = (data["access_token"]!.GetValue<string>(), _clock().AddSeconds(expiresIn));
        _tokens[(index, resource)] = token;
        return token;
    }

    /// <summary>The device's own registration; the next one when Entra ID throttles it.</summary>
    private async Task<(string Token, DateTimeOffset Expires)> TokenForAsync(string deviceId, string resource, CancellationToken ct)
    {
        ThrottledException? last = null;
        for (var shift = 0; shift < config.Apps.Count; shift++)
        {
            try
            {
                return await AppTokenAsync(AppIndex(deviceId, shift), resource, ct);
            }
            catch (ThrottledException e)
            {
                last = e;
            }
        }
        throw last ?? (Exception)new UnavailableException("no registration");
    }

    // -- D365FO ----------------------------------------------------------------------------

    private async Task<JsonObject> D365Async(string resource, string deviceId, string operation, JsonObject request, CancellationToken ct)
    {
        var (token, _) = await TokenForAsync(deviceId, resource, ct);
        using var message = new HttpRequestMessage(HttpMethod.Post, resource + Protocol.ServicePath + operation)
        {
            Content = new StringContent(new JsonObject { ["_request"] = request }.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        message.Headers.Authorization = new("Bearer", token);
        using var response = await http.SendAsync(message, ct);
        var raw = await response.Content.ReadAsStringAsync(ct);
        if (response.StatusCode == HttpStatusCode.TooManyRequests) throw new ThrottledException(RetryAfter(response));
        if (response.StatusCode != HttpStatusCode.OK)
        {
            logger.LogError("D365FO {Operation} answered {Status}: {Body}", operation, (int)response.StatusCode, Clip(raw));
            throw new UnavailableException($"D365FO answered {(int)response.StatusCode}");
        }
        return JsonNode.Parse(raw)!.AsObject();
    }

    private async Task<JsonObject> DeviceKeyAsync(string resource, string deviceId, CancellationToken ct)
    {
        if (_keys.TryGetValue((resource, deviceId), out var cached) && cached.Expires > _clock())
            return cached.Answer;
        var answer = await D365Async(resource, deviceId, "getDeviceKey", new JsonObject { ["DeviceId"] = deviceId }, ct);
        _keys[(resource, deviceId)] = (answer, _clock().AddSeconds(Protocol.DeviceKeyCacheSeconds));
        return answer;
    }

    private string? Allowed(string? url)
    {
        var normalized = Protocol.Normalize(url ?? "");
        return config.D365Urls.Contains(normalized) ? normalized : null;
    }

    // -- endpoints -------------------------------------------------------------------------

    public async Task<Result> RegisterAsync(JsonObject body, CancellationToken ct = default)
    {
        var code = Str(body, "code");
        var deviceId = Str(body, "deviceId").Trim();
        var publicKey = Str(body, "publicKey");
        var signature = Str(body, "signature");
        var resource = Allowed(Str(body, "d365Url"));
        if (code.Length == 0 || deviceId.Length == 0 || publicKey.Length == 0 || deviceId.Length > 200)
            return Fail(400, "INVALID_REQUEST", "Setup code, device and key are required.", success: false);
        if (resource is null)
            return Fail(400, "UNKNOWN_ENVIRONMENT", "This token service does not serve that D365FO environment.", success: false);
        if (!Protocol.VerifyDeviceSignature(publicKey, Protocol.RegisterText(code, deviceId, publicKey), signature))
            return Fail(400, "BAD_SIGNATURE", "The device key signature is not valid.", success: false);
        JsonObject answer;
        try
        {
            answer = await D365Async(resource, deviceId, "registerDeviceKey", new JsonObject
            {
                ["Code"] = code.Trim().ToUpperInvariant(),
                ["DeviceId"] = deviceId,
                ["PublicKey"] = publicKey,
                ["DeviceBrand"] = Clip(Str(body, "deviceBrand"), 60),
                ["DeviceModel"] = Clip(Str(body, "deviceModel"), 60),
                ["Platform"] = Clip(Str(body, "platform"), 20),
                ["PlatformVersion"] = Clip(Str(body, "platformVersion"), 20),
                ["AppVersionName"] = Clip(Str(body, "appVersionName"), 40),
            }, ct);
        }
        catch (ThrottledException e)
        {
            return Busy("The token service is busy. Try again shortly.", e);
        }
        catch (UnavailableException e)
        {
            return Fail(503, "D365_UNAVAILABLE", $"D365FO could not be reached: {e.Message}");
        }
        _keys.TryRemove((resource, deviceId), out _);
        return new Result(200, new JsonObject
        {
            ["success"] = Bool(answer, "Success"),
            ["errorCode"] = Str(answer, "ErrorCode"),
            ["message"] = Str(answer, "Message"),
            ["company"] = Str(answer, "Company"),
            ["deviceName"] = Str(answer, "DeviceName"),
            ["activeDevices"] = Int(answer, "ActiveDevices"),
            ["maxDevices"] = Int(answer, "MaxDevices"),
        });
    }

    public async Task<Result> TokenAsync(JsonObject body, CancellationToken ct = default)
    {
        var deviceId = Str(body, "deviceId").Trim();
        var nonce = Str(body, "nonce");
        var signature = Str(body, "signature");
        var resource = Allowed(Str(body, "resource"));
        long.TryParse(Str(body, "timestamp"), out var timestamp);
        if (deviceId.Length == 0 || nonce.Length == 0 || nonce.Length > 64 || signature.Length == 0 || timestamp == 0)
            return Fail(400, "INVALID_REQUEST", "deviceId, timestamp, nonce and signature are required.");
        if (resource is null)
            return Fail(400, "UNKNOWN_ENVIRONMENT", "This token service does not serve that D365FO environment.");
        if (Math.Abs(_clock().ToUnixTimeSeconds() - timestamp) > Protocol.ClockSkewSeconds)
            return Fail(401, "CLOCK_SKEW", "The device clock is wrong. Set the date and time automatically.");
        JsonObject device;
        try
        {
            device = await DeviceKeyAsync(resource, deviceId, ct);
        }
        catch (ThrottledException e)
        {
            return Busy("The token service is busy.", e);
        }
        catch (UnavailableException e)
        {
            return Fail(503, "D365_UNAVAILABLE", $"D365FO could not be reached: {e.Message}");
        }
        if (!Bool(device, "Success") && Str(device, "ErrorCode") == "DEVICE_UNKNOWN")
            return Fail(403, "DEVICE_UNKNOWN", "This device has not been set up. Scan a device setup code.");
        // The device signs the resource exactly as it sent it (less a trailing slash).
        var signedResource = Str(body, "resource").Trim().TrimEnd('/');
        if (!Protocol.VerifyDeviceSignature(Str(device, "PublicKey"), Protocol.TokenText(deviceId, signedResource, timestamp, nonce), signature))
            return Fail(401, "BAD_SIGNATURE", "The device key signature is not valid. Set the device up again.");
        if (!Bool(device, "IsActive"))
            return Fail(403, "DEVICE_INACTIVE", "This device has been deactivated in D365FO.");
        if (!Bool(device, "LicenseValid"))
            return Fail(403, Or(Str(device, "ErrorCode"), "LICENSE_INVALID"), Or(Str(device, "Message"), "The app's license is not valid."));
        if (!await nonces.UseAsync(deviceId, nonce, timestamp, ct))
            return Fail(401, "REPLAY", "This request was already used.");
        try
        {
            var (token, expires) = await TokenForAsync(deviceId, resource, ct);
            return new Result(200, new JsonObject
            {
                ["access_token"] = token,
                ["expires_in"] = Math.Max(0, (long)(expires - _clock()).TotalSeconds),
            });
        }
        catch (ThrottledException e)
        {
            return Busy("Entra ID is busy.", e);
        }
        catch (UnavailableException e)
        {
            return Fail(503, "AAD_UNAVAILABLE", e.Message);
        }
    }

    // -- helpers ---------------------------------------------------------------------------

    private static Result Fail(int status, string code, string message, bool? success = null)
    {
        var body = new JsonObject();
        if (success is not null) body["success"] = success.Value;
        body["errorCode"] = code;
        body["message"] = message;
        return new Result(status, body);
    }

    private static Result Busy(string message, ThrottledException e) =>
        new(429, new JsonObject { ["errorCode"] = "BUSY", ["message"] = message },
            new Dictionary<string, string> { ["Retry-After"] = e.RetryAfter.ToString() });

    private static int RetryAfter(HttpResponseMessage response) =>
        response.Headers.RetryAfter?.Delta is { } d ? (int)d.TotalSeconds
        : response.Headers.TryGetValues("Retry-After", out var v) && int.TryParse(v.FirstOrDefault(), out var s) ? s
        : 30;

    /// <summary>A JSON value as text: strings as they are, numbers and booleans as written, null as "".</summary>
    private static string Str(JsonObject o, string name) => o[name] switch
    {
        null => "",
        JsonValue v when v.TryGetValue<string>(out var s) => s,
        var node => node.ToJsonString(),
    };

    private static bool Bool(JsonObject o, string name) =>
        o[name] is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    private static long Int(JsonObject o, string name) =>
        o[name] is JsonValue v && (v.TryGetValue<long>(out var n) || long.TryParse(v.ToString(), out n)) ? n : 0;

    private static string Or(string value, string fallback) => value.Length > 0 ? value : fallback;

    private static string Clip(string s, int max = 300) => s.Length <= max ? s : s[..max];
}
