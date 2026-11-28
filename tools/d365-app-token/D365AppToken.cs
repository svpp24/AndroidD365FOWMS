// The token code of the HSMobileOps token function (azure-token-function/TokenFunction:
// TokenService.AppTokenAsync + Azure.cs ManagedIdentityAssertion), as one class to drop into a
// C# application. NuGet: Azure.Identity.
//
// No client secret: the app's managed identity gets a token for api://AzureADTokenExchange, and
// Entra ID accepts that as the app registration's client assertion (a federated credential on the
// registration that trusts the managed identity). The D365FO token that comes back belongs to the
// app registration, which D365FO knows under System administration > Microsoft Entra applications.
//
// It works only where a managed identity exists: Azure (App Service, Functions, VM, Container
// Apps, AKS) or an Azure Arc-enabled server. A program on an ordinary PC or on-premises server
// has no managed identity - use a certificate on the app registration instead (see the end).

using System.Collections.Concurrent;
using System.Net;
using System.Text.Json.Nodes;
using Azure.Core;
using Azure.Identity;

public sealed class D365AppToken(
    string tenantId,                 // the customer's Entra tenant ID
    string clientId,                 // the app registration listed in D365FO's Microsoft Entra applications
    HttpClient http,
    string? userAssignedIdentityClientId = null) // null: the system-assigned identity
{
    private const string TokenExchangeAudience = "api://AzureADTokenExchange";
    private const string AssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private readonly ManagedIdentityCredential _identity = userAssignedIdentityClientId is null
        ? new ManagedIdentityCredential(ManagedIdentityId.SystemAssigned)
        : new ManagedIdentityCredential(ManagedIdentityId.FromUserAssignedClientId(userAssignedIdentityClientId));
    private readonly ConcurrentDictionary<string, (string Token, DateTimeOffset Expires)> _tokens = new();
    private readonly SemaphoreSlim _lock = new(1, 1);

    /// <summary>
    /// A D365FO access token for <paramref name="d365Url"/> (e.g. https://contoso.operations.dynamics.com),
    /// cached until 5 minutes before it expires.
    /// </summary>
    public async Task<string> GetTokenAsync(string d365Url, CancellationToken ct = default)
    {
        var resource = d365Url.Trim().TrimEnd('/');
        if (_tokens.TryGetValue(resource, out var cached) && cached.Expires - TimeSpan.FromMinutes(5) > DateTimeOffset.UtcNow)
            return cached.Token;

        await _lock.WaitAsync(ct);
        try
        {
            if (_tokens.TryGetValue(resource, out cached) && cached.Expires - TimeSpan.FromMinutes(5) > DateTimeOffset.UtcNow)
                return cached.Token;

            // 1. The managed identity's token for the token-exchange audience: the client assertion.
            var assertion = (await _identity.GetTokenAsync(
                new TokenRequestContext([TokenExchangeAudience + "/.default"]), ct)).Token;

            // 2. Client credentials for the app registration, with the assertion instead of a secret.
            var form = new Dictionary<string, string>
            {
                ["grant_type"] = "client_credentials",
                ["client_id"] = clientId,
                ["scope"] = resource + "/.default",
                ["client_assertion_type"] = AssertionType,
                ["client_assertion"] = assertion,
            };
            using var response = await http.PostAsync(
                $"https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token", new FormUrlEncodedContent(form), ct);
            var raw = await response.Content.ReadAsStringAsync(ct);
            if (response.StatusCode == HttpStatusCode.TooManyRequests)
                throw new HttpRequestException("Entra ID is throttling this registration; retry after "
                    + (response.Headers.RetryAfter?.Delta?.TotalSeconds ?? 30) + " s", null, response.StatusCode);
            if (response.StatusCode != HttpStatusCode.OK)
                throw new HttpRequestException($"Entra ID answered {(int)response.StatusCode}: {raw}", null, response.StatusCode);

            var data = JsonNode.Parse(raw)!.AsObject();
            var expiresIn = data["expires_in"] is { } e ? int.Parse(e.ToString()) : 3600;
            var token = (data["access_token"]!.GetValue<string>(), DateTimeOffset.UtcNow.AddSeconds(expiresIn));
            _tokens[resource] = token;
            return token.Item1;
        }
        finally
        {
            _lock.Release();
        }
    }
}

// Use:
//   var tokens = new D365AppToken("<tenant-id>", "<client-id>", new HttpClient());
//   var token  = await tokens.GetTokenAsync("https://contoso.operations.dynamics.com");
//   request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
//
// Outside Azure (no managed identity), same token without a secret, with a certificate on the app
// registration (NuGet: Microsoft.Identity.Client):
//   var app = ConfidentialClientApplicationBuilder.Create(clientId).WithTenantId(tenantId)
//       .WithCertificate(certFromWindowsStore).Build();
//   var r = await app.AcquireTokenForClient([d365Url + "/.default"]).ExecuteAsync();
