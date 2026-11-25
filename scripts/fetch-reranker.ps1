param([switch]$VerifyOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelRoot = Join-Path $projectRoot 'src\main\resources\model\bge-reranker-model'
$revision = '90213ffc6a8e6f051a6331269a0f5526cdd896f6'
$modelUrl = "https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/$revision"
$files = @(
    @{
        Name = 'model_quantized.onnx'
        RemotePath = 'onnx/model_quantized.onnx'
        Sha256 = '912fc1215c2dbff6499700534bd8d31253af01573861abbfc43afd1fab6cce5d'
    },
    @{
        Name = 'tokenizer.json'
        RemotePath = 'tokenizer.json'
        Sha256 = '8bf8afbfd11306bd872018c53bfdf2e160a56f8edbcf49933324404791c148d3'
    }
)

foreach ($file in $files) {
    $destination = Join-Path $modelRoot $file.Name
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $file.Sha256) {
            throw "Checksum mismatch for $destination. Move the existing file aside to download the pinned model."
        }
        Write-Host "Verified $($file.Name)."
        continue
    }
    if ($VerifyOnly) {
        throw "Missing $destination. Run scripts/fetch-reranker.ps1 to download it."
    }
    New-Item -ItemType Directory -Path $modelRoot -Force | Out-Null
    $temporary = Join-Path $modelRoot ($file.Name + '.' + [Guid]::NewGuid().ToString('N') + '.partial')
    try {
        Write-Host "Downloading $($file.Name)..."
        & curl.exe --fail --location --retry 2 --progress-bar --output $temporary "$modelUrl/$($file.RemotePath)"
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed for $($file.Name) (curl exit code $LASTEXITCODE)."
        }
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $file.Sha256) {
            throw "Checksum mismatch on downloaded $($file.Name)."
        }
        Move-Item -LiteralPath $temporary -Destination $destination
        Write-Host "Installed and verified $($file.Name)."
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) {
            Remove-Item -LiteralPath $temporary
        }
    }
}
