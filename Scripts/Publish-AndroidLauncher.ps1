param(
    [Parameter(Mandatory = $true)]
    [string]$VersionName,
    [string]$Notes = "零境启动器 Android 一次性安装器更新",
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$GiteeRepository = "xiaojie578/CrossingVoid-Downloader-Android",
    [string]$GiteeBranch = "master",
    [string]$GiteeAccessToken = "",
    [string]$OutputDir = "D:\启动器新包\AndroidInstaller",
    [string]$ServerSshTarget = "crossing-server",
    [string]$WebsiteManifestPath = "C:\inetpub\wwwroot\manifests\launcher\android-latest.json",
    [switch]$RecoveryBuild,
    [switch]$SkipManifest,
    [switch]$ReplaceExistingAsset,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$productKey = "crossingvoid-launcher-android-installer"
$releaseTag = "android-installer-v$VersionName"
$manifestRepositoryPath = "launcher/android-installer-latest.json"
$expectedPackageName = "com.TFAC.CorssingVoid"
$expectedSignerSha256 = "56f1b0b317e38985808ddd9ee03f3785a8c0190bf32ff2791ba6a3f2c7ba2d92"
$attachmentLimitBytes = 100MB

function Write-Stage([string]$Stage, [double]$Percent, [string]$Message) {
    $payload = [ordered]@{ type = 'progress'; stage = $Stage; percent = $Percent; message = $Message }
    Write-Output ("::axtools " + ($payload | ConvertTo-Json -Compress))
}

function Set-PublishCancellation([ValidateSet('stop','locked')][string]$Mode, [string]$Message) {
    Write-Output ("::axtools " + ([ordered]@{ type='cancellation'; mode=$Mode; message=$Message } | ConvertTo-Json -Compress))
}

function Get-GiteeToken {
    if (![string]::IsNullOrWhiteSpace($GiteeAccessToken)) { return $GiteeAccessToken }
    foreach ($name in @("FANTASYTOOLS_GITEE_TOKEN", "GITEE_TOKEN", "GITEE_ACCESS_TOKEN")) {
        foreach ($scope in @("Process", "User", "Machine")) {
            $value = [Environment]::GetEnvironmentVariable($name, $scope)
            if (![string]::IsNullOrWhiteSpace($value)) { return $value }
        }
    }
    throw "未找到 Gitee 访问令牌。"
}

function Get-GiteeApiUrl([string]$Path) {
    $base = "https://gitee.com/api/v5/repos/$GiteeRepository"
    if ([string]::IsNullOrWhiteSpace($Path)) { return $base }
    return "$base/$Path"
}

function Invoke-GiteeApi {
    param([ValidateSet("Get", "Post", "Put", "Patch", "Delete")][string]$Method, [string]$Path, [hashtable]$Body = @{})
    $uri = Get-GiteeApiUrl -Path $Path
    $payload = @{} + $Body
    $payload.access_token = $script:Token
    if ($Method -eq "Get") {
        $query = @($payload.GetEnumerator() | ForEach-Object {
            "{0}={1}" -f [uri]::EscapeDataString([string]$_.Key), [uri]::EscapeDataString([string]$_.Value)
        }) -join "&"
        $separator = if ($uri.Contains("?")) { "&" } else { "?" }
        return Invoke-RestMethod -Method Get -Uri "${uri}$separator$query"
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/x-www-form-urlencoded; charset=utf-8" -Body $payload
}

function Ensure-Release([string]$Tag, [string]$Title, [string]$Body) {
    $repository = Invoke-GiteeApi -Method Get -Path ""
    $existing = $null
    try {
        $existing = Invoke-GiteeApi -Method Get -Path ("releases/tags/{0}" -f [uri]::EscapeDataString($Tag))
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -ne 404) { throw }
    }
    $payload = @{
        tag_name = $Tag
        name = $Title
        body = $Body
        prerelease = $VersionName.EndsWith("-Beta", [StringComparison]::OrdinalIgnoreCase)
        target_commitish = [string]$repository.default_branch
    }
    if ($null -eq $existing) {
        return Invoke-GiteeApi -Method Post -Path "releases" -Body $payload
    }
    return Invoke-GiteeApi -Method Patch -Path ("releases/{0}" -f $existing.id) -Body $payload
}

function Upload-ReleaseAsset([int]$ReleaseId, [string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -gt $attachmentLimitBytes) { throw "启动器 APK 超过 Gitee 100 MiB 附件上限。" }
    $existing = @(Invoke-GiteeApi -Method Get -Path ("releases/{0}/attach_files?per_page=100" -f $ReleaseId))
    $same = @($existing | Where-Object { $_.name -eq $file.Name } | Select-Object -First 1)
    if ($same.Count -gt 0) {
        if ([int64]$same[0].size -eq $file.Length -and !$ReplaceExistingAsset) {
            Write-Host "已存在同名同大小 APK，跳过上传：$($file.Name)" -ForegroundColor DarkGray
            return
        }
        Write-Stage "checkpoint-reset" 74 "断点 APK 不匹配，正在重新上传"
        Write-Host "删除 Gitee 上的同名旧 APK：$($file.Name)" -ForegroundColor Yellow
        Invoke-GiteeApi -Method Delete -Path ("releases/{0}/attach_files/{1}" -f $ReleaseId, $same[0].id) | Out-Null
    }
    $uri = Get-GiteeApiUrl -Path ("releases/{0}/attach_files" -f $ReleaseId)
    & curl.exe --fail-with-body --show-error --progress-bar --request POST `
        --form "access_token=$script:Token" --form "file=@$($file.FullName);filename=$($file.Name)" $uri
    if ($LASTEXITCODE -ne 0) { throw "Gitee APK 上传失败，curl exit code $LASTEXITCODE" }
}

function Test-PublishCheckpoint([string]$ApkPath, [string]$ManifestPath) {
    if (!(Test-Path -LiteralPath $ApkPath -PathType Leaf) -or
        !(Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        return $false
    }
    try {
        $checkpointManifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $checkpointApk = Get-Item -LiteralPath $ApkPath
        $checkpointHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        return [string]$checkpointManifest.versionName -eq $VersionName -and
            [string]$checkpointManifest.asset.fileName -eq $checkpointApk.Name -and
            [int64]$checkpointManifest.asset.sizeBytes -eq $checkpointApk.Length -and
            [string]$checkpointManifest.asset.sha256 -eq $checkpointHash
    }
    catch {
        return $false
    }
}

function Publish-RepositoryFile([string]$LocalPath, [string]$RepositoryPath) {
    $escapedPath = ($RepositoryPath -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
    $current = $null
    try {
        $current = Invoke-GiteeApi -Method Get -Path ("contents/{0}?ref={1}" -f $escapedPath, [uri]::EscapeDataString($GiteeBranch))
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -ne 404) { throw }
    }
    $payload = @{
        branch = $GiteeBranch
        message = "Update Android launcher to $VersionName"
        content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($LocalPath))
    }
    if ($null -ne $current -and ![string]::IsNullOrWhiteSpace([string]$current.sha)) {
        $payload.sha = [string]$current.sha
        Invoke-GiteeApi -Method Put -Path ("contents/{0}" -f $escapedPath) -Body $payload | Out-Null
    } else {
        Invoke-GiteeApi -Method Post -Path ("contents/{0}" -f $escapedPath) -Body $payload | Out-Null
    }
}

function Publish-WebsiteManifest([string]$LocalPath) {
    if (!(Test-Path -LiteralPath $LocalPath -PathType Leaf)) { throw "官网启动器清单不存在：$LocalPath" }
    $remoteTempPath = "C:\Windows\Temp\crossingvoid-android-launcher-$([Guid]::NewGuid().ToString('N')).json"
    & scp $LocalPath "${ServerSshTarget}:$remoteTempPath"
    if ($LASTEXITCODE -ne 0) { throw "上传官网 Android 启动器清单失败，scp exit code $LASTEXITCODE" }
    $remoteScript = @"
`$ErrorActionPreference = 'Stop'
`$temp = '$remoteTempPath'
`$target = '$WebsiteManifestPath'
try {
    `$manifest = Get-Content -LiteralPath `$temp -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]`$manifest.productKey -ne '$productKey' -or [string]`$manifest.versionName -ne '$VersionName') {
        throw '官网 Android 启动器清单内容不匹配'
    }
    `$targetDir = Split-Path -Parent `$target
    New-Item -ItemType Directory -Path `$targetDir -Force | Out-Null
    if (Test-Path -LiteralPath `$target -PathType Leaf) {
        Copy-Item -LiteralPath `$target -Destination "`$target.bak" -Force
    }
    Move-Item -LiteralPath `$temp -Destination `$target -Force
    & icacls.exe `$target /reset | Out-Null
    if (`$LASTEXITCODE -ne 0) { throw '无法恢复官网清单的 IIS 读取权限' }
}
catch {
    Remove-Item -LiteralPath `$temp -Force -ErrorAction SilentlyContinue
    throw
}
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remoteScript))
    & ssh $ServerSshTarget "powershell -NoProfile -EncodedCommand $encoded"
    if ($LASTEXITCODE -ne 0) { throw "发布官网 Android 启动器清单失败，ssh exit code $LASTEXITCODE" }
}

$NormalVersionCode = 1
$RecoveryVersionCode = 1001003
$VersionCode = if ($RecoveryBuild) { $RecoveryVersionCode } else { $NormalVersionCode }
if ($VersionName -notmatch '^\d+\.\d+\.\d+$') { throw "手机版启动器 VersionName 必须使用纯数字三段式：$VersionName" }
$script:Token = if ($DryRun) { "dry-run" } else { Get-GiteeToken }
$safeVersion = $VersionName -replace '[^A-Za-z0-9._-]', '_'
$apkName = "CrossingVoidInstaller-$safeVersion-Android.apk"
$publishedApk = Join-Path $OutputDir $apkName
$manifestFileName = if ($SkipManifest) { "android-installer-$safeVersion.json" } else { "android-installer-latest.json" }
$manifestPath = Join-Path $OutputDir $manifestFileName
$hadCheckpointFiles = (Test-Path -LiteralPath $publishedApk -PathType Leaf) -or
    (Test-Path -LiteralPath $manifestPath -PathType Leaf)
$resumeCheckpoint = Test-PublishCheckpoint -ApkPath $publishedApk -ManifestPath $manifestPath

Set-PublishCancellation 'stop' '构建和上传阶段可停止；再次执行会从有效断点继续。'
if ($resumeCheckpoint) {
    Write-Stage "checkpoint-resume" 62 "检测到 $VersionName 有效发布断点，跳过 npm 和 Gradle 构建"
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $apk = Get-Item -LiteralPath $publishedApk
    $sha256 = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash.ToLowerInvariant()
}
else {
    $ReplaceExistingAsset = $true
    if ($hadCheckpointFiles) {
        Write-Stage "checkpoint-rebuild" 2 "发布断点校验失败，正在重新构建"
    }

Write-Stage "test" 5 "运行 Android 启动器前端测试"
Push-Location $ProjectRoot
try {
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw "前端测试失败。" }
    Write-Stage "frontend" 15 "构建 Android 启动器前端"
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "前端构建失败。" }
    Write-Stage "capacitor" 28 "同步 Capacitor Android 工程"
    & npx.cmd cap sync android
    if ($LASTEXITCODE -ne 0) { throw "Capacitor 同步失败。" }

    $javaHome = if (![string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $env:JAVA_HOME
    } else {
        "C:\Program Files\Java\jdk-23"
    }
    if (!(Test-Path -LiteralPath $javaHome -PathType Container)) { throw "没有找到 JDK 23：$javaHome" }
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    Push-Location (Join-Path $ProjectRoot "android")
    try {
        Write-Stage "gradle" 40 "构建 Android Release APK"
        & .\gradlew.bat assembleRelease "-PlauncherVersionName=$VersionName" "-PlauncherVersionCode=$VersionCode" --console=plain --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "assembleRelease 失败。" }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}

$sourceApk = Join-Path $ProjectRoot "android\app\build\outputs\apk\release\app-release.apk"
if (!(Test-Path -LiteralPath $sourceApk -PathType Leaf)) { throw "Release APK 不存在：$sourceApk" }
$androidSdkRoot = if (![string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$buildTools = Get-ChildItem (Join-Path $androidSdkRoot "build-tools") -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $buildTools) { throw "没有找到 Android SDK build-tools。" }
$aapt = Join-Path $buildTools.FullName "aapt.exe"
$apkSigner = Join-Path $buildTools.FullName "apksigner.bat"
Write-Stage "verify" 54 "校验 APK 包名、版本和签名"
$badging = (& $aapt dump badging $sourceApk | Select-Object -First 1) -join ""
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "name='$([regex]::Escape($expectedPackageName))'") { throw "一次性安装器包名验证失败：$badging" }
if ($badging -notmatch "versionCode='$VersionCode'" -or $badging -notmatch "versionName='$([regex]::Escape($VersionName))'") {
    throw "Release APK 版本验证失败：$badging"
}
$signerOutput = (& $apkSigner verify --verbose --print-certs $sourceApk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Release APK 签名验证失败。" }
$signerMatch = [regex]::Match($signerOutput, 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)')
if (!$signerMatch.Success) { throw "无法读取一次性安装器签名指纹。" }
$actualSignerSha256 = $signerMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant()
if ($actualSignerSha256 -ne $expectedSignerSha256) {
    throw "一次性安装器签名与游戏不一致：$actualSignerSha256"
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $publishedApk -Force

$apk = Get-Item -LiteralPath $publishedApk
$sha256 = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$downloadUrl = "https://gitee.com/$GiteeRepository/releases/download/$releaseTag/$([uri]::EscapeDataString($apk.Name))"
$manifest = [ordered]@{
    schemaVersion = 1
    productKey = $productKey
    versionName = $VersionName
    versionCode = $VersionCode
    notes = $Notes
    publishedAt = (Get-Date).ToUniversalTime().ToString("o")
    asset = [ordered]@{
        fileName = $apk.Name
        url = $downloadUrl
        sizeBytes = $apk.Length
        sha256 = $sha256
    }
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Stage "package" 60 "APK 与更新清单已整理到 AX 工作区"
}

Write-Host "APK：$publishedApk"
Write-Host "SHA256：$sha256"
Write-Host "清单：$manifestPath"
if ($DryRun) {
    Write-Stage "completed" 100 "DryRun 完成"
    return
}

Write-Stage "release" 65 "创建 Gitee Release"
$release = Ensure-Release -Tag $releaseTag -Title "零境启动器 Android Installer $VersionName" -Body $Notes
Write-Stage "upload" 78 "上传 Android 启动器 APK"
Upload-ReleaseAsset -ReleaseId ([int]$release.id) -Path $publishedApk
if (!$SkipManifest) {
    Set-PublishCancellation 'locked' 'APK 已上传，正在提交最新版清单，不可停止。'
    Write-Stage "manifest" 94 "更新 Android 启动器版本清单"
    Publish-RepositoryFile -LocalPath $manifestPath -RepositoryPath $manifestRepositoryPath
    Write-Stage "website" 97 "同步官网 Android 启动器清单"
    Publish-WebsiteManifest -LocalPath $manifestPath
}
Write-Stage "completed" 100 "Android 启动器发布完成"
Write-Host "Gitee Release：https://gitee.com/$GiteeRepository/releases/tag/$releaseTag"
Write-Host "更新清单：https://gitee.com/$GiteeRepository/raw/$GiteeBranch/$manifestRepositoryPath"
Write-Host "官网清单：https://www.crossingvoid.top/manifests/launcher/android-latest.json"
