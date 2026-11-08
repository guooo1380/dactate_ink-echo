$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$taskJdk = (Get-ChildItem -LiteralPath '.tools/java' -Directory | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force -Path 'build/store-tests' | Out-Null
Push-Location -LiteralPath '.tools'
try { & "$taskJdk/bin/jar.exe" xf sqlite-jdbc.jar org/sqlite/native/Windows/x86_64/sqlitejdbc.dll } finally { Pop-Location }
$taskAdapters = @(Get-ChildItem -LiteralPath 'tests/host' -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$taskSources = @('app/src/main/java/com/inkecho/dictation/Engine.java','app/src/main/java/com/inkecho/dictation/WordImport.java','app/src/main/java/com/inkecho/dictation/Store.java','tests/StoreTests.java')
& "$taskJdk/bin/javac.exe" -encoding UTF-8 -d build/store-tests @taskAdapters @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Integration test compilation failed' }
& "$taskJdk/bin/java.exe" '-Dorg.sqlite.lib.path=.tools/org/sqlite/native/Windows/x86_64' -cp 'build/store-tests;.tools/sqlite-jdbc.jar;.tools/slf4j-api.jar;.tools/slf4j-nop.jar' StoreTests
if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
