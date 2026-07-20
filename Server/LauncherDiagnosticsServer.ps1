$ErrorActionPreference = "Stop"

$listenPrefix = "http://127.0.0.1:51988/"
$storageRoot = "C:\ProgramData\CrossingVoidLauncherDiagnostics"
$maxPayloadBytes = 32KB
$maxLogBytes = 10MB
$logUploadPath = "/api/launcher-diagnostics/upload-log"
$rateLimit = @{}
$logUploadRateLimit = @{}

New-Item -ItemType Directory -Force -Path $storageRoot | Out-Null
$uploadRoot = Join-Path $storageRoot "uploads"
New-Item -ItemType Directory -Force -Path $uploadRoot | Out-Null
$serviceLog = Join-Path $storageRoot "service.log"
Add-Content -LiteralPath $serviceLog -Value "$(Get-Date -Format o) starting diagnostics listener"
Get-ChildItem -Path $storageRoot -Filter "*.jsonl" -File -ErrorAction SilentlyContinue |
  Where-Object { $_.LastWriteTimeUtc -lt (Get-Date).ToUniversalTime().AddDays(-21) } |
  Remove-Item -Force
Get-ChildItem -Path $uploadRoot -File -ErrorAction SilentlyContinue |
  Where-Object { $_.LastWriteTimeUtc -lt (Get-Date).ToUniversalTime().AddDays(-21) } |
  Remove-Item -Force

function Write-Response([System.Net.HttpListenerResponse]$response, [int]$statusCode, [object]$payload) {
  $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress))
  $response.StatusCode = $statusCode
  $response.ContentType = "application/json; charset=utf-8"
  $response.ContentLength64 = $bytes.Length
  $response.OutputStream.Write($bytes, 0, $bytes.Length)
  $response.Close()
}

function Read-Text([object]$value, [int]$maxLength) {
  if ($null -eq $value) { return "" }
  $text = [string]$value
  if ($text.Length -le $maxLength) { return $text }
  return $text.Substring(0, $maxLength)
}

function Read-Int([object]$value) {
  try { return [long]$value } catch { return 0 }
}

function Get-SafePathPart([object]$value, [string]$fallback) {
  $text = Read-Text $value 80
  $safe = $text -replace '[^A-Za-z0-9._-]', '_'
  $safe = $safe.Trim('.', '_', '-')
  if ([string]::IsNullOrWhiteSpace($safe)) { return $fallback }
  return $safe
}

function Read-RequestBytes([System.Net.HttpListenerRequest]$request, [int64]$maximumBytes) {
  $output = [IO.MemoryStream]::new()
  $buffer = [byte[]]::new(64KB)
  try {
    while (($count = $request.InputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
      if ($output.Length + $count -gt $maximumBytes) {
        throw [IO.InvalidDataException]::new("Payload too large")
      }
      $output.Write($buffer, 0, $count)
    }
    return $output.ToArray()
  } finally {
    $output.Dispose()
  }
}

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add($listenPrefix)
try {
  $listener.Start()
  Add-Content -LiteralPath $serviceLog -Value "$(Get-Date -Format o) listening on $listenPrefix"
} catch {
  Add-Content -LiteralPath $serviceLog -Value "$(Get-Date -Format o) listener startup failed: $($_.Exception.Message)"
  throw
}

try {
  while ($listener.IsListening) {
    $context = $listener.GetContext()
    try {
      $request = $context.Request
      $path = $request.Url.AbsolutePath.TrimEnd("/")
      if ($request.HttpMethod -eq "GET" -and ($path -eq "/health" -or $path -eq "/api/launcher-diagnostics/health")) {
        Write-Response $context.Response 200 @{ success = $true; service = "launcher-diagnostics" }
        continue
      }

      if ($request.HttpMethod -eq "POST" -and $path -eq $logUploadPath) {
        $productKey = $request.Headers["X-Product-Key"]
        $installationId = $request.Headers["X-Installation-Id"]
        $launcherVersion = $request.Headers["X-Launcher-Version"]
        if ($productKey -ne "crossingvoid-android-launcher") {
          Write-Response $context.Response 400 @{ success = $false; message = "Unknown product" }
          continue
        }
        if ([string]::IsNullOrWhiteSpace($installationId) -or $installationId -notmatch '^[A-Za-z0-9._-]{16,80}$') {
          Write-Response $context.Response 400 @{ success = $false; message = "Invalid installation id" }
          continue
        }

        $now = [DateTimeOffset]::UtcNow
        $uploadRateKey = "$($request.RemoteEndPoint.Address)|$installationId"
        $uploadRateEntry = $logUploadRateLimit[$uploadRateKey]
        if ($null -eq $uploadRateEntry -or $uploadRateEntry.Window.AddMinutes(10) -lt $now) {
          $uploadRateEntry = [PSCustomObject]@{ Window = $now; Count = 0 }
          $logUploadRateLimit[$uploadRateKey] = $uploadRateEntry
        }
        $uploadRateEntry.Count++
        if ($uploadRateEntry.Count -gt 6) {
          Write-Response $context.Response 429 @{ success = $false; message = "Log upload rate limit exceeded" }
          continue
        }

        if ($request.ContentLength64 -gt $maxLogBytes) {
          Write-Response $context.Response 413 @{ success = $false; message = "Log file exceeds 10 MiB" }
          continue
        }
        try {
          $content = Read-RequestBytes $request $maxLogBytes
        } catch [IO.InvalidDataException] {
          Write-Response $context.Response 413 @{ success = $false; message = "Log file exceeds 10 MiB" }
          continue
        }
        if ($content.Length -le 0) {
          Write-Response $context.Response 400 @{ success = $false; message = "Log file is empty" }
          continue
        }

        $safeInstallationId = Get-SafePathPart $installationId "unknown-installation"
        $safeVersion = Get-SafePathPart $launcherVersion "unknown-version"
        $baseName = "launcher-log-{0:yyyyMMdd-HHmmssfff}-{1}-{2}" -f (Get-Date), $safeInstallationId, $safeVersion
        $logPath = Join-Path $uploadRoot "$baseName.log"
        $metadataPath = Join-Path $uploadRoot "$baseName.json"
        [IO.File]::WriteAllBytes($logPath, $content)
        $metadata = [ordered]@{
          receivedAt = $now.ToString("o")
          remoteAddress = $request.RemoteEndPoint.Address.ToString()
          installationId = $safeInstallationId
          launcherVersion = $safeVersion
          sizeBytes = $content.Length
          fileName = [IO.Path]::GetFileName($logPath)
        }
        Set-Content -LiteralPath $metadataPath -Value ($metadata | ConvertTo-Json) -Encoding utf8
        Write-Response $context.Response 200 @{ success = $true; fileName = $metadata.fileName; sizeBytes = $content.Length }
        continue
      }

      if ($request.HttpMethod -ne "POST" -or $path -ne "/api/launcher-diagnostics/report") {
        Write-Response $context.Response 404 @{ success = $false; message = "Not found" }
        continue
      }

      if ($request.ContentLength64 -gt $maxPayloadBytes) {
        Write-Response $context.Response 413 @{ success = $false; message = "Payload too large" }
        continue
      }

      $ip = $request.RemoteEndPoint.Address.ToString()
      $now = [DateTimeOffset]::UtcNow
      $entry = $rateLimit[$ip]
      if ($null -eq $entry -or $entry.Window.AddMinutes(1) -lt $now) {
        $entry = [PSCustomObject]@{ Window = $now; Count = 0 }
        $rateLimit[$ip] = $entry
      }
      $entry.Count++
      if ($entry.Count -gt 30) {
        Write-Response $context.Response 429 @{ success = $false; message = "Rate limit exceeded" }
        continue
      }

      $reader = [IO.StreamReader]::new($request.InputStream, $request.ContentEncoding)
      $body = $reader.ReadToEnd()
      $reader.Dispose()
      $payload = $body | ConvertFrom-Json
      if ($payload.productKey -ne "crossingvoid-android-launcher") {
        Write-Response $context.Response 400 @{ success = $false; message = "Unknown product" }
        continue
      }

      $diagnostic = [ordered]@{
        receivedAt = $now.ToString("o")
        remoteAddress = $ip
        stage = Read-Text $payload.stage 80
        message = Read-Text $payload.message 1200
        launcherVersion = Read-Text $payload.launcherVersion 48
        gameVersion = Read-Text $payload.gameVersion 48
        gameVersionCode = Read-Int $payload.gameVersionCode
        gameLastUpdateTime = Read-Int $payload.gameLastUpdateTime
        targetVersion = Read-Text $payload.targetVersion 48
        phase = Read-Text $payload.phase 48
        nativeStatus = Read-Text $payload.nativeStatus 48
        source = Read-Text $payload.source 32
      }
      $file = Join-Path $storageRoot ("android-launcher-{0:yyyy-MM-dd}.jsonl" -f (Get-Date))
      Add-Content -LiteralPath $file -Value ($diagnostic | ConvertTo-Json -Compress) -Encoding utf8
      Write-Response $context.Response 200 @{ success = $true }
    } catch {
      try { Write-Response $context.Response 500 @{ success = $false; message = "Diagnostic storage failed" } } catch {}
    }
  }
} finally {
  if ($listener.IsListening) { $listener.Stop() }
  $listener.Close()
  Add-Content -LiteralPath $serviceLog -Value "$(Get-Date -Format o) listener stopped"
}
