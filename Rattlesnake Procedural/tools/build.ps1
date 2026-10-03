<#
.SYNOPSIS
    Compila cleo\Snake_Procedural.sc com o gta3sc (GTA3Script).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\build.ps1
    powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Verify

.NOTES
    Requer git, cmake e um compilador C++ (Visual Studio Build Tools servem).
    Se o gta3sc ja estiver compilado, defina a variavel de ambiente GTA3SC
    apontando para o executavel.
#>
[CmdletBinding()]
param(
    [switch] $Verify,
    [string] $Output
)

$ErrorActionPreference = "Stop"

$revision = "e9b4c3035c77b013f57af8595bc76b777acf73f6"
$packageDir = Split-Path -Parent $PSScriptRoot
$target = Join-Path $packageDir "cleo\Snake_Procedural.cs"
$workDir = if ($env:GTA3SC_DIR) { $env:GTA3SC_DIR } else { Join-Path $env:TEMP "gta3sc-$revision" }

if (-not $Output) { $Output = $target }
if ($Verify -and $Output -eq $target) {
    $Output = Join-Path $env:TEMP ("snake-procedural-" + [guid]::NewGuid().ToString("N") + ".cs")
}

$compiler = $env:GTA3SC
if (-not $compiler) {
    $compiler = Join-Path $workDir "build\Release\gta3sc.exe"
    if (-not (Test-Path $compiler)) { $compiler = Join-Path $workDir "build\gta3sc.exe" }

    if (-not (Test-Path $compiler)) {
        if (-not (Test-Path (Join-Path $workDir ".git"))) {
            Write-Host "==> clonando gta3sc ($revision)"
            git clone --quiet https://github.com/thelink2012/gta3sc $workDir
        }
        git -C $workDir fetch --quiet --depth 1 origin $revision
        git -C $workDir checkout --quiet $revision

        Write-Host "==> compilando gta3sc"
        cmake -S $workDir -B (Join-Path $workDir "build") -DCMAKE_POLICY_VERSION_MINIMUM=3.5 | Out-Null
        cmake --build (Join-Path $workDir "build") --config Release | Out-Null
    }
}

if (-not (Test-Path $compiler)) {
    throw "compilador gta3sc nao encontrado em $compiler"
}

Write-Host "==> compilando Snake_Procedural.sc"
Push-Location $packageDir
try {
    & $compiler compile "cleo\Snake_Procedural.sc" `
        --config=gtasa `
        --cs `
        --guesser `
        -fno-entity-tracking `
        -fbreak-continue `
        --add-config="./tools/cleo_plus_commands.xml" `
        -o $Output
    if ($LASTEXITCODE -ne 0) { throw "gta3sc falhou com codigo $LASTEXITCODE" }
}
finally {
    Pop-Location
}

if ($Verify) {
    Write-Host "==> validando pacote"
    python (Join-Path $packageDir "tools\validate_release.py") --compiled $Output
    if ($LASTEXITCODE -ne 0) { throw "validacao falhou" }
}
