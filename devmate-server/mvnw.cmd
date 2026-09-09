@REM Maven Wrapper startup script. The Maven distribution is downloaded on first use.
@ECHO OFF
SETLOCAL
SET "BASE_DIR=%~dp0"
SET "MAVEN_VERSION=3.9.10"
IF DEFINED MAVEN_USER_HOME (SET "WRAPPER_HOME=%MAVEN_USER_HOME%") ELSE (SET "WRAPPER_HOME=%USERPROFILE%\.m2")
SET "MAVEN_HOME=%WRAPPER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_BIN=%MAVEN_HOME%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"

IF NOT EXIST "%MAVEN_BIN%" (
  IF NOT EXIST "%MAVEN_HOME%" MKDIR "%MAVEN_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$url = (Select-String -Path '%BASE_DIR%.mvn\wrapper\maven-wrapper.properties' -Pattern '^distributionUrl=').Line.Split('=',2)[1];" ^
    "$archive = '%MAVEN_HOME%\apache-maven-%MAVEN_VERSION%-bin.zip';" ^
    "Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive;" ^
    "Expand-Archive -Force -Path $archive -DestinationPath '%MAVEN_HOME%';" ^
    "Remove-Item $archive"
  IF ERRORLEVEL 1 EXIT /B 1
)

CALL "%MAVEN_BIN%" %*
ENDLOCAL
