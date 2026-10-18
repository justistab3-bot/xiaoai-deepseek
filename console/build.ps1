$ErrorActionPreference = 'Stop'

$Sdk   = "C:\Users\Tab3\AppData\Local\Android\Sdk"
$Bt    = Join-Path $Sdk "build-tools\36.0.0"
$Jar   = Join-Path $Sdk "platforms\android-36\android.jar"
$Root  = "D:\worker2\xiaoai\console"
$Build = Join-Path $Root "build"
$Out   = "D:\worker2\xiaoai\out"

if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force -Path "$Build\classes", "$Build\dex", $Out | Out-Null

Write-Host "[1/6] aapt2 link"
& "$Bt\aapt2.exe" link `
    -o "$Build\base.apk" `
    --manifest "$Root\AndroidManifest.xml" `
    -I $Jar `
    --min-sdk-version 26 --target-sdk-version 28 `
    --version-code 1 --version-name "1.0"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/6] javac"
$files = Get-ChildItem -Path "$Root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -nowarn -encoding UTF-8 --release 8 -classpath $Jar -d "$Build\classes" $files 2>&1 |
    Where-Object { $_ -notmatch 'warning|ע:' }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/6] d8"
$classes = Get-ChildItem -Path "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$Bt\d8.bat" --min-api 26 --lib $Jar --output "$Build\dex" $classes 2>&1 | Select-Object -Last 3
if (-not (Test-Path "$Build\dex\classes.dex")) { throw "d8 failed" }

Write-Host "[4/6] pack"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$Build\base.apk", 'Update')
$e = $zip.GetEntry('classes.dex'); if ($e) { $e.Delete() }
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$Build\dex\classes.dex", 'classes.dex', 'Optimal')
$zip.Dispose()

Write-Host "[5/6] zipalign"
& "$Bt\zipalign.exe" -f -p 4 "$Build\base.apk" "$Build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
$ks = "D:\worker2\xiaoai\app\debug.keystore"
$keytool = "C:\Program Files\Java\latest\jdk-26\bin\keytool.exe"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass android -alias androiddebugkey `
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}
$final = Join-Path $Out "xiaoai-console.apk"
if (Test-Path $final) { Remove-Item -Force $final }
& "$Bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true --out $final "$Build\aligned.apk" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "OK -> $final  ($([math]::Round((Get-Item $final).Length/1kb,1)) KB)"
