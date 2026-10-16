@echo off
rem Starts the Microbot client with JDK 17.
setlocal
cd /d "%~dp0"

set "JAVA_HOME="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME (
	echo JDK 17 not found. Install it: winget install EclipseAdoptium.Temurin.17.JDK
	pause
	exit /b 1
)

set "JAR="
for %%F in ("runelite-client\build\libs\client-*-shaded.jar") do set "JAR=%%~fF"
if not defined JAR (
	echo Building Microbot, this takes a few minutes the first time...
	call gradlew.bat :client:assemble || (pause & exit /b 1)
	for %%F in ("runelite-client\build\libs\client-*-shaded.jar") do set "JAR=%%~fF"
)

rem The client offers a Jagex account login itself when no valid session is saved.
rem Pass --jagex-login to switch account: Start-Microbot.bat --jagex-login
start "Microbot" "%JAVA_HOME%\bin\javaw.exe" -jar "%JAR%" %*
