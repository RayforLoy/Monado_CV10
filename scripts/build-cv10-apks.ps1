param([Parameter(Mandatory)][string]$OutputDir)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$apks = @('arm64-v8a', 'armeabi-v7a')
$version = '0.1.4-rc.1'
$bt = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$key = Join-Path $env:USERPROFILE '.android/debug.keystore'
if (!(Test-Path $key)) { throw "Missing local Android development signing key: $key" }
$dest = (New-Item -ItemType Directory -Force $OutputDir).FullName
Push-Location $root
try {
    foreach ($abi in $apks) {
        & .\gradlew.bat --console=plain --max-workers=6 "-Pcv10Abi=$abi" :src:xrt:targets:openxr_android:packageOutOfProcessRelease *> "$dest/$abi-build.log"
        if ($LASTEXITCODE) { throw "Build failed for $abi. See $dest/$abi-build.log" }
        $name = "Monado-CV10-$version-$abi.apk"
        $inputApk = Get-Item src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/release/openxr_android-outOfProcess-release-unsigned.apk
        if (!$inputApk) { throw "Unsigned APK missing" }
        & "$bt/zipalign.exe" -f -P 16 4 $inputApk.FullName "$dest/aligned.tmp.apk"
        if ($LASTEXITCODE) { throw 'zipalign failed' }
        & "$bt/apksigner.bat" sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --v4-signing-enabled false --out "$dest/$name" "$dest/aligned.tmp.apk"
        if ($LASTEXITCODE) { throw 'Signing failed' }
        & "$bt/apksigner.bat" verify --verbose --print-certs "$dest/$name" > "$dest/$abi-signature.txt"
        if ($LASTEXITCODE) { throw 'Signature verification failed' }
        & "$bt/zipalign.exe" -c -P 16 4 "$dest/$name"
        if ($LASTEXITCODE) { throw 'Alignment verification failed' }
    }
    $checksums = foreach ($abi in $apks) {
        $name = "Monado-CV10-$version-$abi.apk"
        (Get-FileHash "$dest/$name" -Algorithm SHA256).Hash.ToLowerInvariant() + "  " + $name
    }
    $checksums | Set-Content "$dest/SHA256SUMS.txt" -Encoding ascii
} finally { Pop-Location }
