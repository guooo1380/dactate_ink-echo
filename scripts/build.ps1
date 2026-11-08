param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskManifest = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath 'app/src/main/AndroidManifest.xml')
$taskVersion = $taskManifest.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$taskApk = "releases/InkAndEcho-$taskVersion.apk"
$taskJdk = if ($env:INK_ECHO_JAVA) { $env:INK_ECHO_JAVA } else { (Get-ChildItem -LiteralPath '.tools/java' -Directory | Select-Object -First 1).FullName }
$taskBuildTools = if ($env:INK_ECHO_BUILD_TOOLS) { $env:INK_ECHO_BUILD_TOOLS } else { Join-Path $taskRoot '.tools/sdk-build/android-15' }
$taskAndroidJar = if ($env:INK_ECHO_ANDROID_JAR) { $env:INK_ECHO_ANDROID_JAR } else { (Get-ChildItem -LiteralPath '.tools/sdk-platform' -Recurse -Filter 'android.jar' | Select-Object -First 1).FullName }
if (!(Test-Path -LiteralPath "$taskJdk/bin/javac.exe") -or !(Test-Path -LiteralPath "$taskBuildTools/aapt2.exe") -or !(Test-Path -LiteralPath $taskAndroidJar)) { throw 'Missing JDK / Android build-tools / android.jar. See README.md.' }
# aapt2 on Windows cannot reliably open an absolute path containing Chinese characters.
$taskAndroidJar = (Resolve-Path -LiteralPath $taskAndroidJar -Relative)
function Invoke-Checked([string]$tool, [string[]]$arguments) { & $tool @arguments; if ($LASTEXITCODE -ne 0) { throw "Build step failed: $tool ($LASTEXITCODE)" } }
New-Item -ItemType Directory -Force -Path 'build/classes','build/dex','build/tests','releases' | Out-Null
$taskClasses = Join-Path 'build' ('classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskClasses | Out-Null
$taskGenerated = $taskClasses + '-resources'
New-Item -ItemType Directory -Path $taskGenerated | Out-Null
if (!$SkipTests) {
    Invoke-Checked "$taskJdk/bin/javac.exe" @('-encoding','UTF-8','-d','build/tests','app/src/main/java/com/inkecho/dictation/Engine.java','app/src/main/java/com/inkecho/dictation/WordImport.java','app/src/main/java/com/inkecho/dictation/AudioCache.java','tests/CoreTests.java','tests/AudioCacheTests.java')
    Invoke-Checked "$taskJdk/bin/java.exe" @('-cp','build/tests','CoreTests')
    Invoke-Checked "$taskJdk/bin/java.exe" @('-cp','build/tests','AudioCacheTests')
}
Invoke-Checked "$taskBuildTools/aapt2.exe" @('compile','--dir','app/src/main/res','-o','build/resources.zip')
Invoke-Checked "$taskBuildTools/aapt2.exe" @('link','-o','build/base.apk','--java',$taskGenerated,'--manifest','app/src/main/AndroidManifest.xml','-I',$taskAndroidJar,'--min-sdk-version','24','--target-sdk-version','35','build/resources.zip')
$taskSources = @(Get-ChildItem -LiteralPath 'app/src/main/java' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$taskSources += @(Get-ChildItem -LiteralPath $taskGenerated -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
Invoke-Checked "$taskJdk/bin/javac.exe" (@('-encoding','UTF-8','--release','8','-classpath',$taskAndroidJar,'-d',$taskClasses) + $taskSources)
Invoke-Checked "$taskJdk/bin/jar.exe" @('cf','build/classes.jar','-C',$taskClasses,'.')
Invoke-Checked "$taskJdk/bin/java.exe" @('-cp',"$taskBuildTools/lib/d8.jar",'com.android.tools.r8.D8','--lib',$taskAndroidJar,'--min-api','24','--output','build/dex','build/classes.jar')
Copy-Item -LiteralPath 'build/base.apk' -Destination 'build/unsigned.apk' -Force
Invoke-Checked "$taskJdk/bin/jar.exe" @('uf','build/unsigned.apk','-C','build/dex','classes.dex')
Invoke-Checked "$taskBuildTools/zipalign.exe" @('-f','-p','4','build/unsigned.apk','build/aligned.apk')
if (!(Test-Path -LiteralPath '.tools/development.jks')) {
    Invoke-Checked "$taskJdk/bin/keytool.exe" @('-genkeypair','-keystore','.tools/development.jks','-storepass','android','-keypass','android','-alias','inkecho','-keyalg','RSA','-keysize','2048','-validity','36500','-dname','CN=Ink and Echo Development')
}
Invoke-Checked "$taskJdk/bin/java.exe" @('-jar',"$taskBuildTools/lib/apksigner.jar",'sign','--ks','.tools/development.jks','--ks-key-alias','inkecho','--ks-pass','pass:android','--key-pass','pass:android','--out',$taskApk,'build/aligned.apk')
Invoke-Checked "$taskJdk/bin/java.exe" @('-jar',"$taskBuildTools/lib/apksigner.jar",'verify','--verbose',$taskApk)
Invoke-Checked "$taskBuildTools/aapt2.exe" @('dump','badging',$taskApk)
Get-FileHash -LiteralPath $taskApk -Algorithm SHA256
Write-Output "APK ready: $taskApk"
