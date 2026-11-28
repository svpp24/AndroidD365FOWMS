using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace HsMobileOps.TokenClient;

/// <summary>
/// Where to get tokens and who this program is. Nothing secret: the setup code is used once and
/// not kept, and the private key stays with Windows. Saved in %LOCALAPPDATA%\HsMobileOps.
/// </summary>
public sealed class Settings
{
    public string DeviceId { get; set; } = "";
    public string D365Url { get; set; } = "";
    public string TokenServiceUrl { get; set; } = "";
    public string Company { get; set; } = "";

    public static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HsMobileOps", "token-client.json");

    public static Settings? Load() =>
        File.Exists(FilePath) ? JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) : null;

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    /// <summary>
    /// Reads a device setup code's QR text: "HSMODEV1:" + base64url of
    /// {"v":1,"label","url","company","token","code"}. Answers the settings and the code.
    /// </summary>
    public static (Settings Settings, string Code) FromSetupQr(string qrText, string deviceId)
    {
        var text = qrText.Trim();
        if (!text.StartsWith("HSMODEV1:", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("A device setup code starts with HSMODEV1:");
        var b64 = text["HSMODEV1:".Length..].Replace('-', '+').Replace('_', '/');
        b64 = b64.PadRight(b64.Length + (4 - b64.Length % 4) % 4, '=');
        var json = JsonNode.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(b64)))!.AsObject();
        var settings = new Settings
        {
            DeviceId = deviceId,
            D365Url = Clean(json["url"]?.ToString()),
            TokenServiceUrl = Clean(json["token"]?.ToString()),
            Company = json["company"]?.ToString() ?? "",
        };
        var code = json["code"]?.ToString() ?? "";
        if (settings.D365Url.Length == 0 || settings.TokenServiceUrl.Length == 0 || code.Length == 0)
            throw new ArgumentException("The setup code has no D365FO address, token service address or code.");
        return (settings, code);
    }

    private static string Clean(string? url) => (url ?? "").Trim().TrimEnd('/');
}
