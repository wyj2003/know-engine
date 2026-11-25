param([switch]$SkipApp, [switch]$SkipMinio, [switch]$SkipMineru, [switch]$FastCompensation)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = $env:KNOW_ENGINE_SERVICES_DIR
if ([string]::IsNullOrWhiteSpace($serviceRoot)) { $serviceRoot = 'D:\develop\know-engine-services' }
$logRoot = Join-Path $serviceRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

function Get-LocalSetting([string]$Property, [string]$EnvironmentVariable) {
    $configPath = Join-Path $projectRoot 'config\local.properties'
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        $pattern = '^\s*' + [regex]::Escape($Property) + '\s*=\s*(.*)$'
        $value = Select-String -LiteralPath $configPath -Pattern $pattern -Encoding utf8 | Select-Object -Last 1
        if ($value) {
            return [regex]::Replace($value.Matches[0].Groups[1].Value, '\\(u[0-9a-fA-F]{4}|.)', {
                param($match)
                $escaped = $match.Groups[1].Value
                if ($escaped.StartsWith('u') -and $escaped.Length -eq 5) {
                    return [string][char][Convert]::ToInt32($escaped.Substring(1), 16)
                }
                return $escaped
            })
        }
    }
    return [Environment]::GetEnvironmentVariable($EnvironmentVariable)
}

function Test-LocalPort([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-LocalService([string]$Name, [string]$Url) {
    Write-Host "Waiting for $Name..."
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ([int]$response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become ready. Check logs under $logRoot."
}

if (-not (Test-LocalPort 9200)) {
    $esRoot = Join-Path $serviceRoot 'elasticsearch-9.5.2'
    Start-Process -FilePath "$esRoot\bin\elasticsearch.bat" -WorkingDirectory $esRoot -WindowStyle Hidden `
        -RedirectStandardOutput "$logRoot\elasticsearch-stdout.log" `
        -RedirectStandardError "$logRoot\elasticsearch-stderr.log" | Out-Null
    Write-Host 'Elasticsearch starting on 127.0.0.1:9200.'
}

if (-not $SkipMinio -and -not (Test-LocalPort 9090)) {
    $env:MINIO_ROOT_USER = Get-LocalSetting 'minio.accessKey' 'KNOW_ENGINE_MINIO_ACCESS_KEY'
    $env:MINIO_ROOT_PASSWORD = Get-LocalSetting 'minio.secretKey' 'KNOW_ENGINE_MINIO_SECRET_KEY'
    if ([string]::IsNullOrWhiteSpace($env:MINIO_ROOT_USER) -or [string]::IsNullOrWhiteSpace($env:MINIO_ROOT_PASSWORD)) {
        throw 'Set the MinIO credentials in config/local.properties or the KNOW_ENGINE_MINIO_* environment variables.'
    }
    $dataRoot = Join-Path $serviceRoot 'minio-data'
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    Start-Process -FilePath "$serviceRoot\minio\minio.exe" -WorkingDirectory $serviceRoot -WindowStyle Hidden `
        -ArgumentList @('server', "`"$dataRoot`"", '--address', '127.0.0.1:9090', '--console-address', '127.0.0.1:9000') `
        -RedirectStandardOutput "$logRoot\minio-stdout.log" `
        -RedirectStandardError "$logRoot\minio-stderr.log" | Out-Null
    Write-Host 'MinIO starting: S3 API 9090, console 9000.'
}

if (-not $SkipMineru -and -not (Test-LocalPort 8000)) {
    $mineruExe = "$serviceRoot\mineru-env\Scripts\mineru-api.exe"
    if (Test-Path -LiteralPath $mineruExe) {
        $modelConfig = "$serviceRoot\mineru-models.json"
        if (Test-Path -LiteralPath $modelConfig) {
            $env:MINERU_MODEL_SOURCE = 'local'
            $env:MINERU_TOOLS_CONFIG_JSON = $modelConfig
        } else {
            $env:MINERU_MODEL_SOURCE = 'modelscope'
        }
        $env:MINERU_DEVICE_MODE = 'cpu'
        $env:MODELSCOPE_CACHE = "$serviceRoot\model-cache"
        $env:OMP_NUM_THREADS = '2'
        $env:MKL_NUM_THREADS = '2'
        $env:MINERU_API_MAX_CONCURRENT_REQUESTS = '1'
        $env:MINERU_PROCESSING_WINDOW_SIZE = '4'
        $env:MINERU_API_OUTPUT_ROOT = "$serviceRoot\mineru-output"
        Start-Process -FilePath $mineruExe -ArgumentList @('--host', '127.0.0.1', '--port', '8000') `
            -WorkingDirectory $serviceRoot -WindowStyle Hidden `
            -RedirectStandardOutput "$logRoot\mineru-stdout.log" `
            -RedirectStandardError "$logRoot\mineru-stderr.log" | Out-Null
        Write-Host 'MinerU starting on 127.0.0.1:8000 (CPU).'
    }
}

if (-not $SkipApp -and -not (Test-LocalPort 8009)) {
    Wait-LocalService 'Elasticsearch' 'http://127.0.0.1:9200/_cluster/health'
    Wait-LocalService 'MinIO' 'http://127.0.0.1:9090/minio/health/live'
    if (-not $SkipMineru) { Wait-LocalService 'MinerU' 'http://127.0.0.1:8000/health' }
    if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
        $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
    }
    if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { throw 'Set DEEPSEEK_API_KEY before starting the application.' }
    $classpathFile = "$projectRoot\target\runtime-classpath.txt"
    if (-not (Test-Path -LiteralPath $classpathFile)) { throw 'Build runtime-classpath.txt with Maven before starting.' }
    $classpath = "$projectRoot\target\classes;" + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $classpath = $classpath.Replace('\', '/')
    $argumentsFile = "$projectRoot\target\local-java-args.txt"
    $arguments = @('-Xms128m', '-Xmx768m', '-XX:ActiveProcessorCount=2', '-Dfile.encoding=UTF-8', '-cp', ('"' + $classpath + '"'), 'know.engine.KnowEngineApplication')
    if ($FastCompensation) {
        $arguments += @('"--document.compensation.embedding-cron=*/10 * * * * ?"', '"--document.compensation.cleanup-cron=*/10 * * * * ?"')
    }
    [IO.File]::WriteAllLines($argumentsFile, $arguments, [Text.UTF8Encoding]::new($false))
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
    Start-Process -FilePath $java -WorkingDirectory $projectRoot -WindowStyle Hidden `
        -ArgumentList ('@' + $argumentsFile) `
        -RedirectStandardOutput "$logRoot\app-stdout.log" `
        -RedirectStandardError "$logRoot\app-stderr.log" | Out-Null
    Write-Host 'know-engine starting on 127.0.0.1:8009.'
    Wait-LocalService 'know-engine' 'http://127.0.0.1:8009/actuator/health'
    Write-Host 'know-engine is ready.'
}
Write-Host "Logs: $logRoot"
