param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Description
)

$ErrorActionPreference = 'Stop'
$gradlePath = Join-Path $PSScriptRoot 'app/build.gradle.kts'
$historyPath = Join-Path $PSScriptRoot 'CHANGELOG.md'
$gradleText = [IO.File]::ReadAllText($gradlePath)
$codePattern = 'versionCode\s*=\s*(\d+)'
$namePattern = 'versionName\s*=\s*"(\d+)\.(\d+)"'
$codeMatches = [regex]::Matches($gradleText, $codePattern)
$nameMatches = [regex]::Matches($gradleText, $namePattern)
if ($codeMatches.Count -ne 1 -or $nameMatches.Count -ne 1) {
    throw 'No se encontro una unica version numerica en app/build.gradle.kts.'
}
$nextCode = [long]$codeMatches[0].Groups[1].Value + 1
if ($nextCode -gt 2100000000) { throw 'Se alcanzo el limite de versionCode.' }
$versionParts = $nameMatches[0].Groups
$nextMinor = [long]$versionParts[2].Value + 1
$nextVersion = '{0}.{1}' -f $versionParts[1].Value, $nextMinor
$updatedGradle = [regex]::Replace($gradleText, $codePattern, "versionCode = $nextCode")
$updatedGradle = [regex]::Replace($updatedGradle, $namePattern, ('versionName = "' + $nextVersion + '"'))
$descriptionLine = ($Description -replace '[\r\n]+', ' ').Trim()
if (!$descriptionLine) { throw 'Describe el cambio antes de aumentar la version.' }
$history = if (Test-Path -LiteralPath $historyPath) { [IO.File]::ReadAllText($historyPath) } else { "# Historial de versiones`n" }
$entry = "`n## $nextVersion - $(Get-Date -Format 'yyyy-MM-dd')`n`n- $descriptionLine`n"
$encoding = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($historyPath, $history + $entry, $encoding)
try {
    [IO.File]::WriteAllText($gradlePath, $updatedGradle, $encoding)
} catch {
    [IO.File]::WriteAllText($historyPath, $history, $encoding)
    throw
}
Write-Output "Version $nextVersion"
Write-Output 'Sincroniza Gradle y vuelve a instalar la app para ver la nueva version en Perfil.'
