using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace HsMobileOps.TokenClient;

/// <summary>
/// Gets D365FO access tokens from the customer's token service the way a handheld does
/// (docs/device-setup.md): no client secret. The program has its own EC P-256 key, kept by
/// Windows (CNG, not exportable); it registers the public key once with a device setup code,
/// then signs each token request. To D365FO it is one more device under Mobile devices: it takes
/// a slot of the license's device limit, and deactivating it there stops its tokens within 5 min.
/// </summary>
public sealed class DeviceTokenClient(Settings settings, HttpClient http)
{
    private string? _token;
    private DateTimeOffset _expires;

    /// <summary>The device key; made on first use and kept by Windows under a name per device.</summary>
    private ECDsaCng Key()
    {
        var name = "hsmo_device_key_v1_" + settings.DeviceId;
        var key = CngKey.Exists(name)
            ? CngKey.Open(name)
            : CngKey.Create(CngAlgorithm.ECDsaP256, name, new CngKeyCreationParameters
            {
                ExportPolicy = CngExportPolicies.None,
                KeyCreationOptions = CngKeyCreationOptions.None,
            });
        return new ECDsaCng(key);
    }

    /// <summary>SHA256withECDSA, base64 of the DER signature - what the token service verifies.</summary>
    private static string Sign(ECDsa key, string text) => Convert.ToBase64String(
        key.SignData(Encoding.UTF8.GetBytes(text), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));

    /// <summary>Registers this program's key with a device setup code. Answers the service's JSON.</summary>
    public async Task<JsonObject> RegisterAsync(string setupCode, CancellationToken ct = default)
    {
        using var key = Key();
        var code = setupCode.Trim().ToUpperInvariant();
        var publicKey = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        var body = new JsonObject
        {
            ["code"] = code,
            ["deviceId"] = settings.DeviceId,
            ["publicKey"] = publicKey,
            ["signature"] = Sign(key, $"HSMODEV1-REGISTER\n{code}\n{settings.DeviceId}\n{publicKey}"),
            ["deviceBrand"] = "Windows",
            ["deviceModel"] = Environment.MachineName,
            ["platform"] = "Windows",
            ["platformVersion"] = Environment.OSVersion.Version.ToString(),
            ["appVersionName"] = "token-client-1.0",
            ["d365Url"] = settings.D365Url,
            ["company"] = settings.Company,
        };
        using var response = await http.PostAsJsonAsync(settings.TokenServiceUrl + "/api/register", body, ct);
        return await ReadJson(response, ct);
    }

    /// <summary>
    /// A D365FO access token: the one held while it has more than 5 minutes left, else a new one
    /// from the token service. Throws <see cref="TokenException"/> with the service's error code.
    /// </summary>
    public async Task<string> GetTokenAsync(CancellationToken ct = default)
    {
        if (_token is not null && _expires - DateTimeOffset.UtcNow > TimeSpan.FromMinutes(5)) return _token;
        for (var attempt = 1; ; attempt++)
        {
            using var key = Key();
            var resource = settings.D365Url;
            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            var nonce = Guid.NewGuid().ToString("N");
            var body = new JsonObject
            {
                ["deviceId"] = settings.DeviceId,
                ["resource"] = resource,
                ["timestamp"] = timestamp,
                ["nonce"] = nonce,
                ["signature"] = Sign(key, $"HSMODEV1-TOKEN\n{settings.DeviceId}\n{resource}\n{timestamp}\n{nonce}"),
            };
            using var response = await http.PostAsJsonAsync(settings.TokenServiceUrl + "/api/token", body, ct);
            // The service is busy: wait as it says, a few times.
            if (response.StatusCode == HttpStatusCode.TooManyRequests && attempt < 4)
            {
                var wait = response.Headers.RetryAfter?.Delta ?? TimeSpan.FromSeconds(5 * attempt);
                await Task.Delay(wait, ct);
                continue;
            }
            var json = await ReadJson(response, ct);
            if (!response.IsSuccessStatusCode || json["access_token"] is null)
            {
                throw new TokenException((int)response.StatusCode,
                    json["errorCode"]?.ToString() ?? "HTTP_" + (int)response.StatusCode,
                    json["message"]?.ToString() ?? response.ReasonPhrase ?? "");
            }
            _token = json["access_token"]!.ToString();
            _expires = DateTimeOffset.UtcNow.AddSeconds(json["expires_in"] is { } e ? double.Parse(e.ToString()) : 3600);
            return _token;
        }
    }

    /// <summary>Removes this program's key from Windows (after the device is deactivated or retired).</summary>
    public void DeleteKey()
    {
        var name = "hsmo_device_key_v1_" + settings.DeviceId;
        if (CngKey.Exists(name)) CngKey.Open(name).Delete();
        _token = null;
    }

    private static async Task<JsonObject> ReadJson(HttpResponseMessage response, CancellationToken ct)
    {
        var text = await response.Content.ReadAsStringAsync(ct);
        try
        {
            return JsonNode.Parse(text) as JsonObject ?? [];
        }
        catch (JsonException)
        {
            return new JsonObject { ["errorCode"] = "HTTP_" + (int)response.StatusCode, ["message"] = text.Length > 300 ? text[..300] : text };
        }
    }
}

/// <summary>The token service refused: DEVICE_INACTIVE, DEVICE_UNKNOWN, a license code, BAD_SIGNATURE...</summary>
public sealed class TokenException(int status, string errorCode, string message)
    : Exception($"{errorCode} ({status}): {message}")
{
    public int Status { get; } = status;
    public string ErrorCode { get; } = errorCode;
}
