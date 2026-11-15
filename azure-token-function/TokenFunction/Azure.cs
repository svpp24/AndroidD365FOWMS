// The Azure pieces behind TokenService: the managed identity and the replay table.
using System.Security.Cryptography;
using System.Text;
using Azure;
using Azure.Core;
using Azure.Data.Tables;
using Azure.Identity;

namespace HsMobileOps.TokenFunction;

/// <summary>
/// The Function App's system-assigned identity: a token for api://AzureADTokenExchange, the
/// credential every app registration trusts (federated credential). Azure.Identity caches it and
/// renews it before it expires.
/// </summary>
public sealed class ManagedIdentityAssertion : IAssertionSource
{
    private readonly ManagedIdentityCredential _credential = new(ManagedIdentityId.SystemAssigned);
    private static readonly TokenRequestContext Request = new([Protocol.TokenExchangeAudience + "/.default"]);

    public async Task<string> GetAssertionAsync(CancellationToken ct)
    {
        try
        {
            return (await _credential.GetTokenAsync(Request, ct)).Token;
        }
        catch (AuthenticationFailedException e)
        {
            throw new UnavailableException("this Function App's managed identity is not available: " + e.Message);
        }
    }
}

/// <summary>
/// One row per used nonce, partitioned by 10-minute bucket; the hourly cleanup drops buckets older
/// than an hour. In the Function App's own storage account (AzureWebJobsStorage).
/// </summary>
public sealed class TableNonces : INonceStore
{
    public const string TableName = "UsedNonces";
    private const long BucketSeconds = 600;
    private readonly TableClient _table;
    private bool _created;

    public TableNonces(TableServiceClient service) => _table = service.GetTableClient(TableName);

    /// <summary>
    /// The storage behind AzureWebJobsStorage: a connection string, or - when the Function App
    /// reaches its storage with its identity (AzureWebJobsStorage__accountName) - that account,
    /// with the identity (it needs Storage Table Data Contributor).
    /// </summary>
    public static TableServiceClient ServiceFromSettings(Func<string, string?> setting)
    {
        var connection = setting("AzureWebJobsStorage");
        if (!string.IsNullOrWhiteSpace(connection))
            return new TableServiceClient(connection);
        var endpoint = setting("AzureWebJobsStorage__tableServiceUri");
        var account = setting("AzureWebJobsStorage__accountName");
        if (string.IsNullOrWhiteSpace(endpoint) && !string.IsNullOrWhiteSpace(account))
            endpoint = $"https://{account}.table.core.windows.net";
        if (string.IsNullOrWhiteSpace(endpoint))
            throw new InvalidOperationException("No AzureWebJobsStorage setting - the replay check needs the Function App's storage.");
        var clientId = setting("AzureWebJobsStorage__clientId");
        TokenCredential credential = string.IsNullOrWhiteSpace(clientId)
            ? new ManagedIdentityCredential(ManagedIdentityId.SystemAssigned)
            : new ManagedIdentityCredential(ManagedIdentityId.FromUserAssignedClientId(clientId));
        return new TableServiceClient(new Uri(endpoint), credential);
    }

    public static string Bucket(long unixSeconds) => (unixSeconds / BucketSeconds).ToString("D12");

    public async Task<bool> UseAsync(string deviceId, string nonce, long timestamp, CancellationToken ct)
    {
        await EnsureTableAsync(ct);
        var row = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes($"{deviceId}\n{nonce}")))[..40];
        try
        {
            await _table.AddEntityAsync(new TableEntity(Bucket(timestamp), row), ct);
            return true;
        }
        catch (RequestFailedException e) when (e.Status == 409)
        {
            return false;
        }
    }

    public async Task<int> CleanupAsync(DateTimeOffset now, CancellationToken ct)
    {
        await EnsureTableAsync(ct);
        var cutoff = Bucket(now.ToUnixTimeSeconds() - 3600);
        var removed = 0;
        await foreach (var e in _table.QueryAsync<TableEntity>(x => x.PartitionKey.CompareTo(cutoff) < 0,
                           select: ["PartitionKey", "RowKey"], cancellationToken: ct))
        {
            await _table.DeleteEntityAsync(e.PartitionKey, e.RowKey, ETag.All, ct);
            removed++;
        }
        return removed;
    }

    private async Task EnsureTableAsync(CancellationToken ct)
    {
        if (_created) return;
        await _table.CreateIfNotExistsAsync(ct);
        _created = true;
    }
}
