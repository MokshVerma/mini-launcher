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

$ks = "$env:USERPROFILE\.android\debug.keystore"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    & "$j\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\minilauncher.apk" "$out\aligned.apk"
Get-Item "$out\minilauncher.apk" | Select Name, Length
