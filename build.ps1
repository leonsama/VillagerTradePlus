param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$candidates = @($JavaHome, "$env:APPDATA\.hmcl\java\windows-x86_64\mojang-java-runtime-epsilon")
$selected = $null
foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path -LiteralPath "$candidate\bin\javac.exe")) {
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath $release) -and ((Get-Content -LiteralPath $release -Raw) -match 'JAVA_VERSION="25[.\"]')) { $selected = $candidate; break }
    }
}
if (!$selected) { throw '未找到 JDK 25，请使用 .\build.ps1 -JavaHome <JDK25目录>' }
$env:JAVA_HOME = $selected
$env:PATH = "$selected\bin;$env:PATH"
# Keep the wrapper distribution and caches inside the project so the build works in confined sandboxes.
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools\gradle-home'
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat --no-daemon clean build
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败：$LASTEXITCODE" }
} finally { Pop-Location }
