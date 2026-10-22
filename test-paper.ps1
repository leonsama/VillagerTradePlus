param([string]$JavaHome = $env:JAVA_HOME, [switch]$AcceptEula)
$ErrorActionPreference = 'Stop'
if (!$AcceptEula) { throw '本脚本运行独立 Minecraft 测试服；请阅读 Minecraft EULA 后使用 -AcceptEula。' }
Push-Location $PSScriptRoot
try {
    & .\build.ps1 -JavaHome $JavaHome
    $javaPath = Join-Path $env:JAVA_HOME 'bin\java.exe'
    New-Item -ItemType Directory -Force -Path .tools,test-server\plugins | Out-Null
    $serverJar = Join-Path $PSScriptRoot '.tools\paper-26.3-211.jar'
    $serverHash = 'f3a5953094696578193b38fa2831e86db254dfbf4817b1ac6cfca55a2d18ca94'
    if (!(Test-Path -LiteralPath $serverJar)) {
        Invoke-WebRequest -Uri "https://fill-data.papermc.io/v1/objects/$serverHash/paper-26.3-211.jar" -OutFile $serverJar
    }
    if ((Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $serverHash) { throw 'Paper JAR 校验失败' }
    & $javaPath '-Dpaperclip.patchonly=true' -jar $serverJar
    if ($LASTEXITCODE -ne 0) { throw 'Paper 文件展开失败' }
    & .\gradlew.bat --no-daemon smokeJar smokeRunnerJar
    if ($LASTEXITCODE -ne 0) { throw '服务端测试插件构建失败' }
    # Remove only the previous test JAR, whose internal plugin name was TradePlus.
    if (Test-Path -LiteralPath test-server\plugins\TradePlus.jar) {
        Remove-Item -LiteralPath test-server\plugins\TradePlus.jar
    }
    if (Test-Path -LiteralPath test-server\plugins\TradePlusTest.jar) {
        Remove-Item -LiteralPath test-server\plugins\TradePlusTest.jar
    }
    Copy-Item -LiteralPath build\libs\VillagerTradePlus-0.1.4-smoke.jar -Destination test-server\plugins\VillagerTradePlus.jar
    Copy-Item -LiteralPath build\libs\VillagerTradePlusTest-0.1.4.jar -Destination test-server\plugins\VillagerTradePlusTest.jar
    # Integration tests must not inflate the public launch counter.
    New-Item -ItemType Directory -Force -Path test-server\plugins\VillagerTradePlus | Out-Null
    $testConfig = (Get-Content -LiteralPath src\main\resources\config.yml -Raw) -replace '(launch-counter:\s*\r?\n\s*enabled:) true', '$1 false'
    Set-Content -LiteralPath test-server\plugins\VillagerTradePlus\config.yml -Value $testConfig -Encoding utf8
    @'
server-ip=127.0.0.1
server-port=25579
online-mode=false
level-name=tradeplus-test
spawn-protection=0
view-distance=2
simulation-distance=2
pause-when-empty-seconds=-1
enable-rcon=false
enable-query=false
'@ | Set-Content -LiteralPath test-server\server.properties -Encoding ascii
    'eula=true' | Set-Content -LiteralPath test-server\eula.txt -Encoding ascii
    $repositoryProperty = "-DbundlerRepoDir=$PSScriptRoot"
    Push-Location test-server
    try {
        foreach ($round in 1..2) {
            & $javaPath '-Dterminal.jline=false' '-Dterminal.ansi=false' $repositoryProperty -Xms512M -Xmx1536M -jar $serverJar --nogui --noconsole *> "run-$round.log"
            if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath "run-$round.log" -Pattern 'SMOKE SUCCESS:' -Quiet)) { throw "第 $round 轮服务端验证失败，查看 test-server/run-$round.log" }
            Get-Content -LiteralPath smoke-results.txt -Tail 1
        }
    } finally { Pop-Location }
} finally { Pop-Location }
