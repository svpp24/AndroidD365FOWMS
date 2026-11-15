<#
.SYNOPSIS
  Sets up the Mobile Ops token service in the customer's own Azure tenant (docs/device-setup.md).

.DESCRIPTION
  Run by the customer's Azure admin, signed in with `az login` to the right tenant and subscription.
  Creates: a resource group, a storage account, a Python Function App with a managed identity, and
  1-10 app registrations for D365FO. No secrets: each app registration trusts the Function App's
  managed identity (a federated credential), so there is nothing to store, leak or renew. Then
  deploys this folder's code.

  Afterwards, in D365FO:
    1. System administration > Setup > Microsoft Entra applications: add each client ID printed
       at the end, with the user the handhelds' integration already uses.
    2. Mobile devices > Device setup codes > New device setup code: Token service = the URL printed.

  Re-running is safe: existing resources are kept; -AppCount above the current number adds
  registrations. -RemoveSecrets deletes client secrets an older version of this script made.

.EXAMPLE
  ./setup.ps1 -Name contoso-mobileops -Location westeurope -D365Urls https://contoso.operations.dynamics.com -AppCount 3
#>
param(
    [Parameter(Mandatory)] [ValidatePattern('^[a-z0-9-]{3,40}$')] [string] $Name,
    [Parameter(Mandatory)] [string] $Location,
    [Parameter(Mandatory)] [string[]] $D365Urls,
    [ValidateRange(1, 10)] [int] $AppCount = 2,
    [string] $ResourceGroup = "$Name-rg",
    [switch] $RemoveSecrets
)
$ErrorActionPreference = 'Stop'
function Az { $out = & az @args; if ($LASTEXITCODE -ne 0) { throw "az $($args -join ' ') failed" }; $out }
function Exists { & az @args -o none 2>$null; return ($LASTEXITCODE -eq 0) }

$tenant = Az account show --query tenantId -o tsv
$subscription = Az account show --query id -o tsv
Write-Host "Tenant $tenant, subscription $subscription"
foreach ($u in $D365Urls) { if ($u -notmatch '^https://') { throw "D365FO URL must start with https://: $u" } }

# Storage account names: 3-24 lower-case letters and digits.
$storage = (($Name -replace '-', '') + 'st').Substring(0, [Math]::Min(24, ($Name -replace '-', '').Length + 2))

Az group create -n $ResourceGroup -l $Location -o none
if (-not (Exists storage account show -n $storage -g $ResourceGroup)) {
    Az storage account create -n $storage -g $ResourceGroup -l $Location --sku Standard_LRS --min-tls-version TLS1_2 --allow-blob-public-access false -o none
}
if (-not (Exists functionapp show -n $Name -g $ResourceGroup)) {
    Az functionapp create -n $Name -g $ResourceGroup --storage-account $storage --consumption-plan-location $Location `
        --runtime python --runtime-version 3.11 --functions-version 4 --os-type Linux --https-only true -o none
}
$principal = Az functionapp identity assign -n $Name -g $ResourceGroup --query principalId -o tsv

# App registrations for D365FO: "<name>-d365-1" ... each trusts the Function App's identity.
$fic = @{
    name      = 'mobileops-token-function'
    issuer    = "https://login.microsoftonline.com/$tenant/v2.0"
    subject   = $principal
    audiences = @('api://AzureADTokenExchange')
    description = 'The Mobile Ops token function (its managed identity) - no client secret.'
} | ConvertTo-Json -Compress
$ficFile = Join-Path ([IO.Path]::GetTempPath()) "$Name-fic.json"
Set-Content -Path $ficFile -Value $fic -Encoding utf8
$apps = [System.Collections.Generic.List[string]]::new()
for ($i = 1; $i -le $AppCount; $i++) {
    $display = "$Name-d365-$i"
    $appId = Az ad app list --display-name $display --query '[0].appId' -o tsv
    if (-not $appId) {
        $appId = Az ad app create --display-name $display --sign-in-audience AzureADMyOrg --query appId -o tsv
        Az ad sp create --id $appId -o none
    }
    $subjects = @(Az ad app federated-credential list --id $appId --query '[].subject' -o tsv)
    if ($subjects -notcontains $principal) {
        # A recreated Function App has a new identity: replace the old trust.
        $old = Az ad app federated-credential list --id $appId --query "[?name=='mobileops-token-function'].id" -o tsv
        if ($old) { Az ad app federated-credential delete --id $appId --federated-credential-id $old -o none }
        Az ad app federated-credential create --id $appId --parameters "@$ficFile" -o none
    }
    if ($RemoveSecrets) {
        $keys = @(Az ad app credential list --id $appId --query '[].keyId' -o tsv)
        foreach ($k in $keys) { if ($k) { Az ad app credential delete --id $appId --key-id $k -o none } }
    }
    $apps.Add($appId)
}
Remove-Item $ficFile -ErrorAction SilentlyContinue
$json = ConvertTo-Json -InputObject @($apps) -Compress

Az functionapp config appsettings set -n $Name -g $ResourceGroup -o none --settings `
    "TENANT_ID=$tenant" "D365_URLS=$($D365Urls -join ',')" `
    "APP_REGISTRATIONS=$json" "SCM_DO_BUILD_DURING_DEPLOYMENT=true"

# Deploy this folder (remote build installs requirements.txt).
$zip = Join-Path ([IO.Path]::GetTempPath()) "$Name-token-service.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $PSScriptRoot 'function_app.py'), (Join-Path $PSScriptRoot 'tokenservice.py'), `
    (Join-Path $PSScriptRoot 'host.json'), (Join-Path $PSScriptRoot 'requirements.txt') -DestinationPath $zip
Az functionapp deployment source config-zip -n $Name -g $ResourceGroup --src $zip --build-remote true -o none

$url = "https://" + (Az functionapp show -n $Name -g $ResourceGroup --query defaultHostName -o tsv)
Write-Host ""
Write-Host "Token service: $url" -ForegroundColor Green
Write-Host "  - use it as 'Token service' in D365FO > Mobile devices > Device setup codes > New device setup code"
Write-Host "App registrations to add in D365FO > System administration > Microsoft Entra applications:"
$apps | ForEach-Object { Write-Host "  $_" }
Write-Host "Check: POST $url/api/token with {} should answer 400 INVALID_REQUEST."
