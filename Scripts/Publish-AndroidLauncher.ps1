param(
    [Parameter(Mandatory = $true)]
    [string]$VersionName,
    [Parameter(Mandatory = $true)]
    [int]$VersionCode,
    [string]$Notes = "零境启动器 Android 一次性安装器更新",
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$GiteeRepository = "xiaojie578/CrossingVoid-Downloader-Android",
    [string]$GiteeBranch = "master",
    [string]$GiteeAccessToken = "",
    [string]$OutputDir = "D:\启动器新包\AndroidInstaller",
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
    $payload = [ordered]@{ stage = $Stage; percent = $Percent; message = $Message }
    Write-Output ("::progress" + ($payload | ConvertTo-Json -Compress))
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
        if (!$ReplaceExistingAsset) {
            throw "Gitee 已存在同名但内容可能不同的 APK；确认覆盖时请使用 -ReplaceExistingAsset。"
        }
        Write-Host "删除 Gitee 上的同名旧 APK：$($file.Name)" -ForegroundColor Yellow
        Invoke-GiteeApi -Method Delete -Path ("releases/{0}/attach_files/{1}" -f $ReleaseId, $same[0].id) | Out-Null
    }
    $uri = Get-GiteeApiUrl -Path ("releases/{0}/attach_files" -f $ReleaseId)
    & curl.exe --fail-with-body --show-error --progress-bar --request POST `
        --form "access_token=$script:Token" --form "file=@$($file.FullName);filename=$($file.Name)" $uri
    if ($LASTEXITCODE -ne 0) { throw "Gitee APK 上传失败，curl exit code $LASTEXITCODE" }
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

if ($VersionCode -le 0) { throw "VersionCode 必须大于 0。" }
if ($VersionName -notmatch '^\d+\.\d+\.\d+$') { throw "手机版启动器 VersionName 必须使用纯数字三段式：$VersionName" }
$script:Token = if ($DryRun) { "dry-run" } else { Get-GiteeToken }

Write-Stage "build" 5 "构建 Android 启动器"
Push-Location $ProjectRoot
try {
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw "前端测试失败。" }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "前端构建失败。" }
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
$safeVersion = $VersionName -replace '[^A-Za-z0-9._-]', '_'
$apkName = "CrossingVoidInstaller-$safeVersion-Android.apk"
$publishedApk = Join-Path $OutputDir $apkName
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
$manifestFileName = if ($SkipManifest) { "android-installer-$safeVersion.json" } else { "android-installer-latest.json" }
$manifestPath = Join-Path $OutputDir $manifestFileName
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

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
    Write-Stage "manifest" 94 "更新 Android 启动器版本清单"
    Publish-RepositoryFile -LocalPath $manifestPath -RepositoryPath $manifestRepositoryPath
}
Write-Stage "completed" 100 "Android 启动器发布完成"
Write-Host "Gitee Release：https://gitee.com/$GiteeRepository/releases/tag/$releaseTag"
Write-Host "更新清单：https://gitee.com/$GiteeRepository/raw/$GiteeBranch/$manifestRepositoryPath"
