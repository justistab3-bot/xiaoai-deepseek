$ErrorActionPreference = 'Stop'

$Sdk   = "C:\Users\Tab3\AppData\Local\Android\Sdk"
$Bt    = Join-Path $Sdk "build-tools\36.0.0"
$Jar   = Join-Path $Sdk "platforms\android-36\android.jar"
$Root  = "D:\worker2\xiaoai\bridge"
$Build = Join-Path $Root "build"
$Out   = "D:\worker2\xiaoai\out"

if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force -Path "$Build\stubs", "$Build\classes", "$Build\dex", "$Build\res", $Out | Out-Null

Write-Host "[1/7] compile Xposed API stubs"
$stubFiles = Get-ChildItem -Path "$Root\stub" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -nowarn -encoding UTF-8 --release 8 -classpath $Jar -d "$Build\stubs" $stubFiles
if ($LASTEXITCODE -ne 0) { throw "stub javac failed" }

Write-Host "[2/7] compile module"
$srcFiles = Get-ChildItem -Path "$Root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -nowarn -encoding UTF-8 --release 8 -classpath "$Jar;$Build\stubs" -d "$Build\classes" $srcFiles
if ($LASTEXITCODE -ne 0) { throw "module javac failed" }

Write-Host "[3/7] aapt2 compile res"
& "$Bt\aapt2.exe" compile --dir "$Root\res" -o "$Build\res\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[4/7] aapt2 link"
& "$Bt\aapt2.exe" link `
    -o "$Build\base.apk" `
    --manifest "$Root\AndroidManifest.xml" `
    -I $Jar `
    -A "$Root\assets" `
    --min-sdk-version 26 --target-sdk-version 28 `
    --version-code 1 --version-name "1.0" `
    "$Build\res\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[5/7] d8"
$classes = Get-ChildItem -Path "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$Bt\d8.bat" --min-api 26 --lib $Jar --output "$Build\dex" $classes 2>&1 | Select-Object -Last 4
if (-not (Test-Path "$Build\dex\classes.dex")) { throw "d8 failed" }

Write-Host "[6/7] pack + align"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$Build\base.apk", 'Update')
$e = $zip.GetEntry('classes.dex'); if ($e) { $e.Delete() }
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$Build\dex\classes.dex", 'classes.dex', 'Optimal')
$zip.Dispose()
& "$Bt\zipalign.exe" -f -p 4 "$Build\base.apk" "$Build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign"
$ks = "D:\worker2\xiaoai\app\debug.keystore"
$keytool = "C:\Program Files\Java\latest\jdk-26\bin\keytool.exe"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass android -alias androiddebugkey `
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}
$final = Join-Path $Out "xiaoai-bridge.apk"
if (Test-Path $final) { Remove-Item -Force $final }
& "$Bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true --out $final "$Build\aligned.apk" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "OK -> $final  ($([math]::Round((Get-Item $final).Length/1kb,1)) KB)"
