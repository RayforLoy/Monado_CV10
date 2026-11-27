param(
    [Parameter(Mandatory)][string]$OutputDir,
    [switch]$UnifiedPreview
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$version = '0.1.4-rc.1'
$builds = if ($UnifiedPreview) {
    @{ Name = "Monado-CV10-$version.apk"; Arguments = @('-Pcv10LauncherPreview=true') }
} else {
    foreach ($abi in 'arm64-v8a', 'armeabi-v7a') {
        @{ Name = "Monado-CV10-$version-$abi.apk"; Arguments = @("-Pcv10Abi=$abi") }
    }
}
$bt = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$key = Join-Path $env:USERPROFILE '.android/debug.keystore'
if (!(Test-Path $key)) { throw "Missing local Android development signing key: $key" }
$dest = (New-Item -ItemType Directory -Force $OutputDir).FullName
Push-Location $root
try {
    foreach ($build in $builds) {
        $name = $build.Name
        $buildLog = "$dest/$name-build.log"
        $gradleArguments = $build.Arguments
        & .\gradlew.bat --console=plain --max-workers=6 @gradleArguments :src:xrt:targets:openxr_android:packageOutOfProcessRelease *> $buildLog
        if ($LASTEXITCODE) { throw "Build failed. See $buildLog" }
        $inputApk = Get-Item src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/release/openxr_android-outOfProcess-release-unsigned.apk
        if (!$inputApk) { throw "Unsigned APK missing" }
        & "$bt/zipalign.exe" -f -P 16 4 $inputApk.FullName "$dest/aligned.tmp.apk"
        if ($LASTEXITCODE) { throw 'zipalign failed' }
        & "$bt/apksigner.bat" sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --v4-signing-enabled false --out "$dest/$name" "$dest/aligned.tmp.apk"
        if ($LASTEXITCODE) { throw 'Signing failed' }
        & "$bt/apksigner.bat" verify --verbose --print-certs "$dest/$name" > "$dest/$name-signature.txt"
        if ($LASTEXITCODE) { throw 'Signature verification failed' }
        & "$bt/zipalign.exe" -c -P 16 4 "$dest/$name"
        if ($LASTEXITCODE) { throw 'Alignment verification failed' }
    }
    $checksums = foreach ($build in $builds) {
        $name = $build.Name
        (Get-FileHash "$dest/$name" -Algorithm SHA256).Hash.ToLowerInvariant() + "  " + $name
    }
    $checksums | Set-Content "$dest/SHA256SUMS.txt" -Encoding ascii
} finally { Pop-Location }
