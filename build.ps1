param([string]$JdkPath, [string]$SdkPath, [switch]$Offline)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($JdkPath) { $env:JAVA_HOME = $JdkPath }
if (-not $env:JAVA_HOME) {
    $chatJdkCandidates = @('C:\Program Files\Android\Android Studio\jbr', 'D:\Program Files\PyCharm 2025.2.4\jbr')
    $env:JAVA_HOME = $chatJdkCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
}
if (-not $env:JAVA_HOME) { throw '请安装 JDK 21，并使用 -JdkPath 指定目录。' }
if ($SdkPath) { Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir=' + $SdkPath.Replace('\','/')) -Encoding utf8 }
if (-not (Test-Path -LiteralPath 'local.properties')) {
    $chatSdkCandidate = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $chatSdkCandidate)) { throw '请安装 Android SDK Platform 35 / Build Tools 35.0.0，并使用 -SdkPath 指定 SDK 目录。' }
    Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir=' + $chatSdkCandidate.Replace('\','/')) -Encoding utf8
}
$chatGradleArgs = @(':core:test', ':video-core:test', ':app:assembleDebug', ':app:lintDebug', '--console=plain')
if ($Offline) { $chatGradleArgs += '--offline' }
& .\gradlew.bat @chatGradleArgs
if ($LASTEXITCODE -ne 0) { throw '构建或验证失败，请查看 Gradle 输出。' }
New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
$chatVersion = [regex]::Match((Get-Content -Raw 'app/build.gradle.kts'), 'versionName = "([^"]+)"').Groups[1].Value
$chatApk = Join-Path 'dist' ("ChatOCR-v" + $chatVersion + '-universal-debug.apk')
Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination $chatApk
Write-Output ("安装包：" + (Resolve-Path -LiteralPath $chatApk))
