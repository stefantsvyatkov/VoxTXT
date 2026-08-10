[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$userProfilePath = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)

$javaCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
if ($null -ne $javaCommand) {
    $javaPath = $javaCommand.Source
} else {
    $androidStudioJava = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
    if (-not (Test-Path -LiteralPath $androidStudioJava)) {
        throw 'java.exe was not found. Install a JDK or configure JAVA_HOME, then run this script again.'
    }
    $javaPath = $androidStudioJava
}

Push-Location $projectRoot
try {
    & $javaPath "-Duser.home=$userProfilePath" -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle release build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
