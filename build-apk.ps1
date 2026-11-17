$ErrorActionPreference = 'Stop'
$root = 'C:\StarFox'
$tools = Join-Path $root '.toolchain'
$manager = Join-Path $root 'manager'

Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
Write-Host '  StarFox APK Builder' -ForegroundColor Cyan
Write-Host '  project: ' -NoNewline -ForegroundColor DarkGray
Write-Host $root -ForegroundColor White
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''

if (-not (Test-Path $manager)) { Write-Host ('[x] manager folder not found: ' + $manager) -ForegroundColor Red; exit 1 }
New-Item -ItemType Directory -Force -Path $tools | Out-Null

function Dl {
    param($Url, $Out, $Label)
    Write-Host ('  [dl] ' + $Label) -ForegroundColor Cyan
    $req = [Net.HttpWebRequest]::Create($Url)
    $req.UserAgent = 'starfox'
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 600000
    $resp = $req.GetResponse()
    $total = $resp.ContentLength
    $si = $resp.GetResponseStream()
    $so = [IO.File]::Create($Out)
    $buf = New-Object byte[] 1048576
    $done = 0
    $last = -1
    while (($n = $si.Read($buf, 0, $buf.Length)) -gt 0) {
        $so.Write($buf, 0, $n)
        $done += $n
        if ($total -gt 0) {
            $pct = [int](($done * 100) / $total)
            if ($pct -ne $last -and ($pct % 10 -eq 0)) {
                Write-Host ('       ' + $pct + '%  ' + [math]::Round($done / 1MB, 1) + ' / ' + [math]::Round($total / 1MB, 1) + ' MB') -ForegroundColor DarkGray
                $last = $pct
            }
        }
    }
    $so.Close(); $si.Close(); $resp.Close()
    if ($total -gt 0 -and $done -ne $total) { throw ('incomplete: ' + $Label) }
    Write-Host ('       ok ' + [math]::Round($done / 1MB, 1) + ' MB') -ForegroundColor Green
}

# ---- 1. JDK 21
$jdk = Join-Path $tools 'jdk21'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host '[1/6] JDK 21' -ForegroundColor Yellow
    $zip = Join-Path $tools 'jdk.zip'
    Dl 'https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip' $zip 'microsoft-jdk-21 (~192 MB)'
    Write-Host '       extracting...' -ForegroundColor DarkGray
    Expand-Archive -Path $zip -DestinationPath $tools -Force
    $inner = Get-ChildItem $tools -Directory | Where-Object { $_.Name -like 'jdk-21*' } | Select-Object -First 1
    if (-not $inner) { throw 'jdk-21 folder not found after extract' }
    Move-Item $inner.FullName $jdk -Force
    Remove-Item $zip -Force
} else {
    Write-Host '[1/6] JDK 21 ready' -ForegroundColor Green
}
$env:JAVA_HOME = $jdk
$env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
Write-Host ('      ' + (& (Join-Path $jdk 'bin\java.exe') -version 2>&1 | Select-Object -First 1)) -ForegroundColor DarkGray

# ---- 2. cmdline-tools
$sdk = Join-Path $tools 'android-sdk'
$sm = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sm)) {
    Write-Host '[2/6] Android cmdline-tools' -ForegroundColor Yellow
    $zip = Join-Path $tools 'cmd.zip'
    $tmp = Join-Path $tools 'cmdtmp'
    Dl 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' $zip 'commandlinetools (~136 MB)'
    Write-Host '       extracting...' -ForegroundColor DarkGray
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest') -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Host '[2/6] cmdline-tools ready' -ForegroundColor Green
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = (Join-Path $sdk 'platform-tools') + ';' + (Join-Path $sdk 'cmdline-tools\latest\bin') + ';' + $env:PATH

# ---- 3. licenses
Write-Host '[3/6] accepting SDK licenses' -ForegroundColor Yellow
$yes = Join-Path $tools 'yes.txt'
(('y' + "`r`n") * 80) | Out-File -FilePath $yes -Encoding ascii
cmd.exe /c "`"$sm`" --sdk_root=`"$sdk`" --licenses < `"$yes`"" 2>&1 | Out-Null

# ---- 4. components
Write-Host '[4/6] installing SDK components (2-3 GB, slow)' -ForegroundColor Yellow
$api = $null
foreach ($p in @('android-37', 'android-36')) {
    Write-Host ('      trying ' + $p) -ForegroundColor DarkGray
    cmd.exe /c "`"$sm`" --sdk_root=`"$sdk`" `"platforms;$p`"" 2>&1 | Out-Null
    if (Test-Path (Join-Path $sdk ('platforms\' + $p))) { $api = $p.Split('-')[1]; break }
}
if (-not $api) { throw 'platform install failed' }
Write-Host ('      platform OK: android-' + $api) -ForegroundColor Green

$bt = if ($api -eq '37') { '37.0.0' } else { '36.0.0' }
cmd.exe /c "`"$sm`" --sdk_root=`"$sdk`" `"build-tools;$bt`"" 2>&1 | Out-Null
if (-not (Test-Path (Join-Path $sdk ('build-tools\' + $bt)))) {
    $bt = '36.0.0'
    cmd.exe /c "`"$sm`" --sdk_root=`"$sdk`" build-tools;36.0.0" 2>&1 | Out-Null
}
Write-Host ('      build-tools OK: ' + $bt) -ForegroundColor Green

$ndk = $null
foreach ($c in @('29.0.14206865', '28.1.13356709', '27.0.12077973')) {
    Write-Host ('      trying ndk ' + $c) -ForegroundColor DarkGray
    cmd.exe /c "`"$sm`" --sdk_root=`"$sdk`" `"ndk;$c`"" 2>&1 | Out-Null
    if (Test-Path (Join-Path $sdk ('ndk\' + $c))) { $ndk = $c; break }
}
if (-not $ndk) { throw 'ndk install failed' }
Write-Host ('      ndk OK: ' + $ndk) -ForegroundColor Green
cmd.exe /c "`"$sm`" --sdk_root=`"$sdk`" platform-tools" 2>&1 | Out-Null

# ---- align gradle
$vf = Join-Path $manager 'gradle\libs.versions.toml'
$t = [IO.File]::ReadAllText($vf)
$t = $t -replace '(?m)^ndk\s*=\s*"[^"]+"', ('ndk = "' + $ndk + '"')
[IO.File]::WriteAllText($vf, $t, (New-Object Text.UTF8Encoding($false)))

$rg = Join-Path $manager 'build.gradle.kts'
$t = [IO.File]::ReadAllText($rg)
$t = $t -replace 'extra\["androidCompileSdkVersion"\]\s*=\s*\d+', ('extra["androidCompileSdkVersion"] = ' + $api)
$t = $t -replace 'extra\["androidTargetSdkVersion"\]\s*=\s*\d+', ('extra["androidTargetSdkVersion"] = ' + $api)
$t = $t -replace 'extra\["androidBuildToolsVersion"\]\s*=\s*"[^"]+"', ('extra["androidBuildToolsVersion"] = "' + $bt + '"')
[IO.File]::WriteAllText($rg, $t, (New-Object Text.UTF8Encoding($false)))

# ---- 5. local.properties + keystore
$esc = $sdk -replace '\\', '\\' -replace ':', '\:'
('sdk.dir=' + $esc) | Out-File -FilePath (Join-Path $manager 'local.properties') -Encoding ascii
Write-Host '[5/6] local.properties written' -ForegroundColor Green

$ks = Join-Path $tools 'starfox.jks'
if (-not (Test-Path $ks)) {
    Write-Host '      generating signing key...' -ForegroundColor DarkGray
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $ks -alias starfox -keyalg RSA -keysize 2048 -validity 10000 -storepass starfox123 -keypass starfox123 -dname 'CN=StarFox,O=StarFox,C=CN' -noprompt 2>&1 | Out-Null
}
# sign.properties 是 Java properties 格式：反斜杠是转义符，路径必须用正斜杠
$ksProp = $ks -replace '\\', '/'
@(
    ('KEYSTORE_FILE=' + $ksProp)
    'KEYSTORE_PASSWORD=starfox123'
    'KEY_ALIAS=starfox'
    'KEY_PASSWORD=starfox123'
) | Out-File -FilePath (Join-Path $manager 'sign.properties') -Encoding ascii
Write-Host ('      sign.properties written (' + $ksProp + ')') -ForegroundColor Green

# ---- 6. build
Write-Host '[6/6] gradle assembleRelease' -ForegroundColor Yellow
Write-Host '      first run downloads gradle + deps, 5-20 min' -ForegroundColor DarkGray
Write-Host ''
Push-Location $manager
try {
    cmd.exe /c 'gradlew.bat :app:assembleRelease -PKSU_PACKAGE_NAME=com.iki.xlh --no-daemon'
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
Write-Host ''
if ($code -ne 0) {
    Write-Host ('[x] gradle failed, exit code ' + $code) -ForegroundColor Red
    exit $code
}

$out = Join-Path $manager 'app\build\outputs\apk\release'
$apks = Get-ChildItem $out -Filter *.apk -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
if (-not $apks) { Write-Host '[x] no apk produced' -ForegroundColor Red; exit 1 }

# ---- 保险：如果只出了 unsigned 包，用 apksigner 现场补签
$signed = $apks | Where-Object { $_.Name -notmatch 'unsigned' }
if (-not $signed) {
    $unsigned = $apks | Select-Object -First 1
    Write-Host ''
    Write-Host ('[!] gradle 只出了 unsigned 包，改用 apksigner 补签') -ForegroundColor Yellow
    $apksigner = Join-Path $sdk ('build-tools\' + $bt + '\apksigner.bat')
    if (-not (Test-Path $apksigner)) { throw ('apksigner not found: ' + $apksigner) }
    $signedPath = Join-Path $out 'starfox-xinglengfu-signed.apk'
    Remove-Item $signedPath -Force -ErrorAction SilentlyContinue
    cmd.exe /c "`"$apksigner`" sign --ks `"$ks`" --ks-pass pass:starfox123 --key-pass pass:starfox123 --out `"$signedPath`" `"$($unsigned.FullName)`"" 2>&1 | Out-Null
    if (-not (Test-Path $signedPath)) { throw 'apksigner failed' }
    $signed = Get-Item $signedPath
    Write-Host ('      补签完成: ' + $signed.Name) -ForegroundColor Green
}
$apks = @($signed) + @($apks | Where-Object { $_.FullName -ne $signed.FullName })

Write-Host '========================================' -ForegroundColor Green
Write-Host '  BUILD OK' -ForegroundColor Green
Write-Host '========================================' -ForegroundColor Green
foreach ($a in $apks) {
    Write-Host ('  ' + $a.FullName) -ForegroundColor White
    Write-Host ('  ' + [math]::Round($a.Length / 1MB, 2) + ' MB') -ForegroundColor DarkGray
}
Write-Host ''
Write-Host ('  adb install -r "' + $apks[0].FullName + '"') -ForegroundColor Cyan
