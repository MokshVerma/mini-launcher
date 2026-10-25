param([switch]$Release, [string]$Version = 'dev')
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Microsoft\jdk-17*")[0].FullName
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$j = "$env:JAVA_HOME\bin"
$here = $PSScriptRoot
$out = "$here\build"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes" | Out-Null

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" --min-sdk-version 21 --target-sdk-version 34
& "$j\javac.exe" -source 8 -target 8 -Xlint:-options -cp $jar -d "$out\classes" (Get-ChildItem "$here\src" -Recurse -Filter *.java).FullName
& "$bt\d8.bat" --release --min-api 21 --lib $jar --output $out (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName

# add classes.dex to the apk
Push-Location $out
& "$j\jar.exe" uf base.apk classes.dex
Pop-Location

$rel = Test-Path "$here\signing.properties"
if ($Release -and -not $rel) { throw "Release build needs release.keystore and signing.properties (both git-ignored)." }
if ($Release) {
    $cfg = @{}
    Get-Content "$here\signing.properties" | ? { $_ -match '=' } | % { $k, $v = $_ -split '=', 2; $cfg[$k.Trim()] = $v.Trim() }
    $ks = "$here\$($cfg.ks)"; $alias = $cfg.alias; $pass = $cfg.password
} else {
    $ks = "$env:USERPROFILE\.android\debug.keystore"; $alias = 'androiddebugkey'; $pass = 'android'
    if (-not (Test-Path $ks)) {
        New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
        & "$j\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    }
}
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias $alias --ks-pass "pass:$pass" --key-pass "pass:$pass" --out "$out\minilauncher.apk" "$out\aligned.apk"
if ($Release) {
    New-Item -ItemType Directory -Force "$here\dist" | Out-Null
    Copy-Item "$out\minilauncher.apk" "$here\dist\mini-launcher-$Version.apk" -Force
}
Get-Item "$out\minilauncher.apk" | Select Name, Length
