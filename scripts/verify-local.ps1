$checks = @(
    @{ Name = 'Application'; Url = 'http://127.0.0.1:8009/upload.html' },
    @{ Name = 'Elasticsearch'; Url = 'http://127.0.0.1:9200/_cluster/health' },
    @{ Name = 'MinIO'; Url = 'http://127.0.0.1:9090/minio/health/live' },
    @{ Name = 'MinerU'; Url = 'http://127.0.0.1:8000/health' }
)
foreach ($check in $checks) {
    try {
        $response = Invoke-WebRequest -Uri $check.Url -UseBasicParsing -TimeoutSec 5
        [pscustomobject]@{ Service = $check.Name; Status = [int]$response.StatusCode; Url = $check.Url }
    } catch {
        [pscustomobject]@{ Service = $check.Name; Status = 'Not ready'; Url = $check.Url }
    }
}
