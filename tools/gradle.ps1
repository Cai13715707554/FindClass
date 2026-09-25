# Local Gradle entry point (Windows PowerShell).
#
# Why this wrapper instead of calling gradle directly:
#   1) On this machine the sandbox forbids writes to %USERPROFILE%\.gradle, so we point
#      GRADLE_USER_HOME at .gradle-home inside the repository.
#   2) The global gradle.properties configures a localhost:7890 proxy that is not reachable
#      here, so the -D flags below clear it and let the JVM connect directly.
#      On a normal dev machine you do NOT need those -D flags; just delete them.
#
# Usage:
#   powershell -File tools/gradle.ps1 testDebugUnitTest
#   powershell -File tools/gradle.ps1 assembleDebug
#   powershell -File tools/gradle.ps1 :core:test
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI unless a BOM is present.

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Tasks
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = Join-Path $repoRoot '.gradle-home'

# Point at the Android SDK unless the caller already set one.
if (-not $env:ANDROID_HOME) {
    foreach ($candidate in @('D:\xiangmu\Projram\Android\SDK', "$env:LOCALAPPDATA\Android\Sdk")) {
        if (Test-Path $candidate) { $env:ANDROID_HOME = $candidate; break }
    }
}
if ($env:ANDROID_HOME) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }

if (-not $Tasks -or $Tasks.Count -eq 0) { $Tasks = @('assembleDebug') }

# Prefer the project wrapper; fall back to a cached distribution.
$userGradle = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
$candidates = @((Join-Path $repoRoot 'gradlew.bat'))
if (Test-Path $userGradle) {
    $candidates += Get-ChildItem $userGradle -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'gradle-8.13\bin\gradle.bat' }
}

$gradle = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $gradle) { throw 'Gradle launcher not found. Run "gradle wrapper" first or install Gradle 8.13.' }

& $gradle @Tasks `
    -Dhttp.proxyHost= -Dhttps.proxyHost= `
    -Dhttp.proxyPort= -Dhttps.proxyPort=
exit $LASTEXITCODE
