$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = (Resolve-Path '.toolchain/jdk-21.0.13+9').Path
$env:ANDROID_HOME = (Resolve-Path '.toolchain/sdk').Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$cacheTarget = Join-Path (Get-Location) '.gradle-home'
New-Item -ItemType Directory -Force $cacheTarget | Out-Null
$cacheAlias = Join-Path $env:TEMP 'bf-gradle'
if (-not (Test-Path $cacheAlias)) { New-Item -ItemType Junction -Path $cacheAlias -Target $cacheTarget | Out-Null }
$projectDirectory = Get-Item -LiteralPath (Get-Location).Path
$expectedCacheTarget = if ($projectDirectory.LinkType -eq 'Junction') { Join-Path $projectDirectory.Target '.gradle-home' } else { $cacheTarget }
if ((Get-Item $cacheAlias).Target -ne $expectedCacheTarget) { throw "Temporary Gradle junction points elsewhere: $cacheAlias" }
$env:GRADLE_USER_HOME = $cacheAlias
$env:ANDROID_USER_HOME = Join-Path $env:TEMP '.android'
$env:ANDROID_SDK_HOME = $env:TEMP
$originalDebugKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (Test-Path $originalDebugKey) { $env:BLUETOOTH_FINDER_DEBUG_KEYSTORE = $originalDebugKey }
$env:JAVA_TOOL_OPTIONS = '-Dhttps.protocols=TLSv1.2 -Djava.net.preferIPv4Stack=true'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
$proxyProcess = $null
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
    $port = 8765
    $client = New-Object Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $port) } catch {
        $proxyProcess = Start-Process -FilePath $python.Source -ArgumentList @('tools/maven_proxy.py', "$port") -WorkingDirectory (Get-Location).Path -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 2
    } finally { $client.Dispose() }
    $env:BLUETOOTH_FINDER_MAVEN_PROXY = "http://127.0.0.1:$port"
}
try {
    & .\.toolchain\gradle-8.11.1\bin\gradle.bat assembleDebug --no-daemon --max-workers=2 '-Dorg.gradle.internal.http.connectionTimeout=15000' '-Dorg.gradle.internal.http.socketTimeout=15000' @args
    exit $LASTEXITCODE
} finally {
    if ($proxyProcess) { Stop-Process -Id $proxyProcess.Id -ErrorAction SilentlyContinue }
}
