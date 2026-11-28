using System.Net.Http.Headers;
using HsMobileOps.TokenClient;

// hsmo-token-client: a D365FO access token without a client secret, the handhelds' way.
//   register "<HSMODEV1:... setup code QR text>" [deviceId]   once per program/machine
//   token [--raw]                                               get a token (masked unless --raw)
//   call <path>                                                 GET <D365 URL>/<path> with the token
//   forget                                                      remove the key and the settings

using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
var command = args.Length > 0 ? args[0].ToLowerInvariant() : "";

try
{
    switch (command)
    {
        case "register":
        {
            if (args.Length < 2) return Usage();
            var deviceId = args.Length > 2 ? args[2] : "PC-" + Environment.MachineName + "-" + Guid.NewGuid().ToString("N")[..6].ToUpperInvariant();
            var (settings, code) = Settings.FromSetupQr(args[1], deviceId);
            var answer = await new DeviceTokenClient(settings, http).RegisterAsync(code);
            Console.WriteLine(answer.ToJsonString());
            if (answer["success"]?.GetValue<bool>() == true)
            {
                settings.Save();
                Console.WriteLine($"Registered as {settings.DeviceId}. Settings: {Settings.FilePath}");
                return 0;
            }
            return 2;
        }
        case "token":
        {
            var client = new DeviceTokenClient(Need(), http);
            var token = await client.GetTokenAsync();
            Console.WriteLine(args.Contains("--raw") ? token : $"Token OK: {token[..12]}... ({token.Length} characters). Use --raw to print it.");
            return 0;
        }
        case "call":
        {
            if (args.Length < 2) return Usage();
            var settings = Need();
            var token = await new DeviceTokenClient(settings, http).GetTokenAsync();
            using var request = new HttpRequestMessage(HttpMethod.Get, settings.D365Url + "/" + args[1].TrimStart('/'));
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var response = await http.SendAsync(request);
            Console.WriteLine($"HTTP {(int)response.StatusCode}");
            var body = await response.Content.ReadAsStringAsync();
            Console.WriteLine(body.Length > 4000 ? body[..4000] + "..." : body);
            return response.IsSuccessStatusCode ? 0 : 3;
        }
        case "selftest":
        {
            // Signs the register and token texts with a throwaway CNG key and verifies them the way
            // the token service does (Protocol.VerifyDeviceSignature).
            var ok = SelfTest.Run();
            Console.WriteLine(ok ? "Self-test passed: signatures verify as the token service checks them." : "Self-test FAILED.");
            return ok ? 0 : 5;
        }
        case "forget":
        {
            var settings = Settings.Load();
            if (settings is not null) new DeviceTokenClient(settings, http).DeleteKey();
            if (File.Exists(Settings.FilePath)) File.Delete(Settings.FilePath);
            Console.WriteLine("Key and settings removed. Registering again needs a new setup code.");
            return 0;
        }
        default:
            return Usage();
    }
}
catch (TokenException e)
{
    Console.Error.WriteLine("Token service refused: " + e.Message);
    if (e.ErrorCode is "DEVICE_INACTIVE" or "DEVICE_UNKNOWN")
        Console.Error.WriteLine("This program's device is not active in D365FO (Mobile devices). Run 'forget', then register with a new setup code.");
    return 4;
}
catch (Exception e) when (e is ArgumentException or HttpRequestException or TaskCanceledException or FormatException)
{
    Console.Error.WriteLine(e.Message);
    return 1;
}

static Settings Need() =>
    Settings.Load() ?? throw new ArgumentException("Not registered yet. Run: register \"HSMODEV1:...\"");

static int Usage()
{
    Console.WriteLine("""
        hsmo-token-client register "<HSMODEV1:... setup code text>" [deviceId]
        hsmo-token-client token [--raw]
        hsmo-token-client call <path, e.g. data/SystemParameters?$top=1>
        hsmo-token-client forget
        """);
    return 1;
}
