<#
.SYNOPSIS
  Sets up the Mobile Ops token service in the customer's own Azure tenant (docs/device-setup.md).

.DESCRIPTION
  Run by the customer's Azure admin, signed in with `az login` to the right tenant and subscription.
  Creates: a resource group, a storage account, a Key Vault, a Python Function App with a managed
  identity, and 1-10 app registrations for D365FO (each with a 2-year secret that goes straight into
  the Key Vault - the secrets are never shown). Then deploys this folder's code.

  Afterwards, in D365FO:
    1. System administration > Setup > Microsoft Entra applications: add each client ID printed
       at the end, with the user the handhelds' integration already uses.
    2. Mobile devices > Device setup codes > New device setup code: Token service = the URL printed.

  Re-running is safe: existing resources are kept; -AppCount above the current number adds
  registrations. Rotate the secrets with -RotateSecrets.

.EXAMPLE
  ./setup.ps1 -Name contoso-mobileops -Location westeurope -D365Urls https://contoso.operations.dynamics.com -AppCount 3
#>
param(
    [Parameter(Mandatory)] [ValidatePattern('^[a-z0-9-]{3,40}$')] [string] $Name,
    [Parameter(Mandatory)] [string] $Location,
    [Parameter(Mandatory)] [string[]] $D365Urls,
    [ValidateRange(1, 10)] [int] $AppCount = 2,
    [string] $ResourceGroup = "$Name-rg",
    [switch] $RotateSecrets
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
$vault = ($Name + '-kv').Substring(0, [Math]::Min(24, $Name.Length + 3))

Az group create -n $ResourceGroup -l $Location -o none
if (-not (Exists storage account show -n $storage -g $ResourceGroup)) {
    Az storage account create -n $storage -g $ResourceGroup -l $Location --sku Standard_LRS --min-tls-version TLS1_2 --allow-blob-public-access false -o none
}
if (-not (Exists keyvault show -n $vault)) {
    Az keyvault create -n $vault -g $ResourceGroup -l $Location --enable-rbac-authorization true -o none
}
if (-not (Exists functionapp show -n $Name -g $ResourceGroup)) {
    Az functionapp create -n $Name -g $ResourceGroup --storage-account $storage --consumption-plan-location $Location `
        --runtime python --runtime-version 3.11 --functions-version 4 --os-type Linux --https-only true -o none
}
$principal = Az functionapp identity assign -n $Name -g $ResourceGroup --query principalId -o tsv
$vaultId = Az keyvault show -n $vault --query id -o tsv
$me = Az ad signed-in-user show --query id -o tsv
az role assignment create --assignee-object-id $principal --assignee-principal-type ServicePrincipal --role 'Key Vault Secrets User' --scope $vaultId -o none 2>$null
az role assignment create --assignee-object-id $me --assignee-principal-type User --role 'Key Vault Secrets Officer' --scope $vaultId -o none 2>$null

# App registrations for D365FO: "<name>-d365-1" ... each with its own secret, kept only in the vault.
$existing = @()
$current = az keyvault secret show --vault-name $vault -n AppRegistrations --query value -o tsv 2>$null
if ($LASTEXITCODE -eq 0 -and $current) { $existing = @($current | ConvertFrom-Json) }
$apps = [System.Collections.Generic.List[object]]::new()
for ($i = 1; $i -le $AppCount; $i++) {
    $display = "$Name-d365-$i"
    $appId = Az ad app list --display-name $display --query '[0].appId' -o tsv
    if (-not $appId) {
        $appId = Az ad app create --display-name $display --sign-in-audience AzureADMyOrg --query appId -o tsv
        Az ad sp create --id $appId -o none
    }
    $known = $existing | Where-Object { $_.id -eq $appId } | Select-Object -First 1
    if ($known -and -not $RotateSecrets) {
        $apps.Add([pscustomobject]@{ id = $appId; secret = $known.secret })
    } else {
        $secret = Az ad app credential reset --id $appId --append --display-name 'mobileops-token-service' --years 2 --query password -o tsv
        $apps.Add([pscustomobject]@{ id = $appId; secret = $secret })
    }
}
$json = ConvertTo-Json -InputObject @($apps) -Compress
# A new role assignment can take a few minutes to apply.
for ($try = 1; ; $try++) {
    & az keyvault secret set --vault-name $vault -n AppRegistrations --value $json -o none 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    if ($try -ge 10) { throw 'Could not write the Key Vault secret - check your Key Vault Secrets Officer role.' }
    Write-Host 'Waiting for Key Vault access...'; Start-Sleep 30
}
$json = $null; $apps = $apps | ForEach-Object { $_.id }
$secretUri = Az keyvault secret show --vault-name $vault -n AppRegistrations --query id -o tsv
$secretUri = $secretUri -replace '/[0-9a-f]{32}$', '/'

Az functionapp config appsettings set -n $Name -g $ResourceGroup -o none --settings `
    "TENANT_ID=$tenant" "D365_URLS=$($D365Urls -join ',')" `
    "APP_REGISTRATIONS=@Microsoft.KeyVault(SecretUri=$secretUri)" "SCM_DO_BUILD_DURING_DEPLOYMENT=true"

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
