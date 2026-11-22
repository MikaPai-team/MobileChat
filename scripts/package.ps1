[CmdletBinding()]
param(
    [ValidateSet('both', 'r8', 'noR8')][string]$Variant = 'both',
    [switch]$Offline,
    [uri]$Proxy
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$targets = @()
if ($Variant -in @('both', 'r8')) { $targets += [pscustomobject]@{ Build = 'release'; Task = ':app:assembleRelease'; Label = 'r8-on'; R8 = $true } }
if ($Variant -in @('both', 'noR8')) { $targets += [pscustomobject]@{ Build = 'releaseNoR8'; Task = ':app:assembleReleaseNoR8'; Label = 'r8-off'; R8 = $false } }
$gradleArguments = @($targets.Task) + '--console=plain'
if ($Offline) { $gradleArguments += '--offline' }
if ($Proxy) {
    foreach ($protocol in @('http', 'https')) {
        $gradleArguments += "-D$protocol.proxyHost=$($Proxy.Host)"
        $gradleArguments += "-D$protocol.proxyPort=$($Proxy.Port)"
    }
}

$androidSdkPath = $env:ANDROID_SDK_ROOT
if (!$androidSdkPath) { $androidSdkPath = $env:ANDROID_HOME }
$localProperties = Join-Path $projectRoot 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) { $androidSdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\').Replace('\ ', ' ') }
}
if (!$androidSdkPath) { throw 'Configure the Android SDK in local.properties or ANDROID_SDK_ROOT.' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $androidSdkPath 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android build-tools are not installed.' }
$apkSigner = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt2.exe'

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Gradle APK build failed.' }
    $outputDirectory = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $results = @()
    foreach ($target in $targets) {
        $apkDirectory = Join-Path $projectRoot "app/build/outputs/apk/$($target.Build)"
        $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
        if ($metadata.applicationId -ne 'com.mobilechat.app' -or $metadata.variantName -ne $target.Build -or @($metadata.elements).Count -ne 1) { throw 'Unexpected APK metadata.' }
        $element = $metadata.elements[0]
        if ($element.versionName -match '[\\/:*?"<>|]' -or (Split-Path -Leaf $element.outputFile) -ne $element.outputFile) { throw 'Invalid artifact filename in APK metadata.' }
        $sourceApk = Join-Path $apkDirectory $element.outputFile
        $manifest = (& $aapt dump xmltree $sourceApk --file AndroidManifest.xml | Out-String)
        if ($LASTEXITCODE -ne 0 -or $manifest -match 'debuggable[^\r\n]*(=true|0xffffffff)') { throw 'Expected a valid non-debuggable APK.' }
        $signature = (& $apkSigner verify --verbose --print-certs $sourceApk | Out-String)
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
        $certificate = [regex]::Match($signature, 'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]+)').Groups[1].Value
        if (!$certificate) { throw 'Cannot read APK signing certificate.' }
        if ($target.R8) {
            $mapping = Join-Path $projectRoot 'app/build/outputs/mapping/release/mapping.txt'
            if (!(Test-Path -LiteralPath $mapping) -or !(Select-String -LiteralPath $mapping -Pattern '^# compiler: R8$' -Quiet)) { throw 'Missing R8 mapping evidence.' }
        }
        $destination = Join-Path $outputDirectory "MobileChat-$($element.versionName)-$($target.Label).apk"
        Copy-Item -LiteralPath $sourceApk -Destination $destination
        $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'APK copy hash mismatch.' }
        "$hash  $(Split-Path -Leaf $destination)" | Set-Content -LiteralPath "$destination.sha256" -Encoding utf8
        $results += [pscustomobject]@{ Path = $destination; Version = $element.versionName; VersionCode = $element.versionCode; R8 = $target.R8; Debuggable = $false; Certificate = $certificate; Bytes = (Get-Item -LiteralPath $destination).Length; SHA256 = $hash }
    }
    if (@($results.Certificate | Select-Object -Unique).Count -ne 1) { throw 'Comparison APKs have different signing certificates.' }
    $results | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $outputDirectory 'package-report.json') -Encoding utf8
    $results | Format-List
} finally { Pop-Location }
