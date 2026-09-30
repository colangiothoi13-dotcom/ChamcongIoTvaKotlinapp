param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,

    [string]$BucketName,

    [string]$RestoreFrom
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) {
    throw "Google Cloud CLI (gcloud) is required. Install it and authenticate first."
}

if (-not [string]::IsNullOrWhiteSpace($RestoreFrom)) {
    if (-not $RestoreFrom.StartsWith("gs://")) {
        throw "RestoreFrom must be the gs:// URI printed by a previous backup."
    }
    & gcloud firestore import $RestoreFrom "--project=$ProjectId"
    if ($LASTEXITCODE -ne 0) { throw "Firestore import failed with exit code $LASTEXITCODE." }
    return
}

$normalizedBucket = $BucketName -replace '^gs://', ''
if ([string]::IsNullOrWhiteSpace($normalizedBucket) -or $normalizedBucket.Contains('/')) {
    throw "BucketName must be a Cloud Storage bucket name, without a path."
}

$backupTime = [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss")
$backupUri = "gs://$normalizedBucket/firestore-backups/$ProjectId/$backupTime"
& gcloud firestore export $backupUri "--project=$ProjectId"
if ($LASTEXITCODE -ne 0) { throw "Firestore export failed with exit code $LASTEXITCODE." }

Write-Output "Firestore backup exported to $backupUri"
