@echo off
if not "%FAKEPLAYER_MAVEN_HOME%"=="" if exist "%FAKEPLAYER_MAVEN_HOME%\bin\mvn.cmd" (
    call "%FAKEPLAYER_MAVEN_HOME%\bin\mvn.cmd" -f "%~dp0pom.xml" %*
    exit /b %errorlevel%
)

where mvn >nul 2>nul
if %errorlevel% equ 0 (
    mvn %*
    exit /b %errorlevel%
)

echo Maven is not installed. Install Maven 3.9.11 or run this project in CI.
exit /b 1
