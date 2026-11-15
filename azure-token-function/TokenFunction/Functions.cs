// Token service - runs in the CUSTOMER's Azure tenant (../README.md). Handhelds set up with a
// device setup code get their D365FO access tokens here instead of holding a client secret.
//
//   POST /api/register  - a new device: setup code + its public key (passed on to D365FO)
//   POST /api/token     - a signed request from a set-up device -> {"access_token", "expires_in"}
//   POST /api/backup    - a deactivated device's signed, last copy of its unsent work (to D365FO)
//   timer nonceCleanup  - hourly, drops replay-check rows older than an hour
//
// Anonymous on purpose: devices have no secret to send. register needs a valid one-off setup
// code; token needs a request signed with a key D365FO holds for an active device.
using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Azure.Functions.Worker.Http;
using Microsoft.Extensions.Logging;

namespace HsMobileOps.TokenFunction;

public sealed class Functions(Lazy<TokenService> service, Lazy<TableNonces> nonces, ILogger<Functions> logger)
{
    [Function("register")]
    public Task<HttpResponseData> Register(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "register")] HttpRequestData req, CancellationToken ct) =>
        HandleAsync(req, "register", ct);

    [Function("token")]
    public Task<HttpResponseData> Token(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "token")] HttpRequestData req, CancellationToken ct) =>
        HandleAsync(req, "token", ct);

    /// <summary>A deactivated device's last copy of its unsent work (TokenService.BackupAsync).</summary>
    [Function("backup")]
    public Task<HttpResponseData> Backup(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "backup")] HttpRequestData req, CancellationToken ct) =>
        HandleAsync(req, "backup", ct);

    [Function("nonceCleanup")]
    public async Task NonceCleanup([TimerTrigger("0 17 * * * *")] TimerInfo timer, CancellationToken ct)
    {
        var removed = await nonces.Value.CleanupAsync(DateTimeOffset.UtcNow, ct);
        logger.LogInformation("nonce cleanup: {Removed} rows removed", removed);
    }

    private async Task<HttpResponseData> HandleAsync(HttpRequestData req, string endpoint, CancellationToken ct)
    {
        JsonObject? body;
        try
        {
            body = JsonNode.Parse(await req.ReadAsStringAsync() ?? "") as JsonObject;
        }
        catch (JsonException)
        {
            body = null;
        }
        if (body is null)
            return await WriteAsync(req, new Result(400, new JsonObject
            {
                ["errorCode"] = "INVALID_REQUEST", ["message"] = "Request body must be a JSON object.",
            }));

        TokenService svc;
        try
        {
            svc = service.Value;
        }
        catch (Exception e)
        {
            logger.LogError(e, "token service is not configured");
            return await WriteAsync(req, new Result(503, new JsonObject
            {
                ["errorCode"] = "NOT_CONFIGURED", ["message"] = "The token service is not configured.",
            }));
        }

        var result = endpoint switch
        {
            "register" => await svc.RegisterAsync(body, ct),
            "backup" => await svc.BackupAsync(body, ct),
            _ => await svc.TokenAsync(body, ct),
        };
        var device = body["deviceId"]?.ToString() ?? "";
        logger.LogInformation("{Endpoint} device={Device} -> {Status} {Error}", endpoint,
            device.Length > 40 ? device[..40] : device, result.HttpStatus, result.Body["errorCode"]?.ToString() ?? "");
        return await WriteAsync(req, result);
    }

    private static async Task<HttpResponseData> WriteAsync(HttpRequestData req, Result result)
    {
        var response = req.CreateResponse((HttpStatusCode)result.HttpStatus);
        response.Headers.Add("Content-Type", "application/json");
        foreach (var (name, value) in result.Headers ?? new Dictionary<string, string>())
            response.Headers.Add(name, value);
        await response.WriteStringAsync(result.Body.ToJsonString());
        return response;
    }
}
