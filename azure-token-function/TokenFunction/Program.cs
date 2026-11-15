using HsMobileOps.TokenFunction;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Azure.Functions.Worker.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

// Settings: TENANT_ID, D365_URLS (comma-separated D365FO URLs this service serves),
// APP_REGISTRATIONS (JSON ["client-id", ...], 1-10 - no secrets: each registration trusts this
// Function App's managed identity), AzureWebJobsStorage (the replay check's table).
var builder = FunctionsApplication.CreateBuilder(args);

builder.Services
    .AddApplicationInsightsTelemetryWorkerService()
    .ConfigureFunctionsApplicationInsights();

builder.Services.AddHttpClient("token-service", c => c.Timeout = TimeSpan.FromSeconds(60));

// Lazy: a missing setting answers 503 NOT_CONFIGURED instead of stopping the host.
builder.Services.AddSingleton(sp => new Lazy<TableNonces>(() =>
    new TableNonces(TableNonces.ServiceFromSettings(Environment.GetEnvironmentVariable))));
builder.Services.AddSingleton(sp => new Lazy<TokenService>(() => new TokenService(
    Config.FromSettings(
        Environment.GetEnvironmentVariable("TENANT_ID"),
        Environment.GetEnvironmentVariable("D365_URLS"),
        Environment.GetEnvironmentVariable("APP_REGISTRATIONS")),
    sp.GetRequiredService<IHttpClientFactory>().CreateClient("token-service"),
    sp.GetRequiredService<Lazy<TableNonces>>().Value,
    new ManagedIdentityAssertion(),
    sp.GetRequiredService<ILoggerFactory>().CreateLogger<TokenService>())));

builder.Build().Run();
