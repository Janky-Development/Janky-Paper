@echo off
rem Janky Paper - builds the server jar for every supported Minecraft version (or the ones given) into dist\.
rem A shortcut for the Gradle tasks in the "janky versions" group (build.gradle.kts):
rem   build-all-versions.bat               gradlew buildAllJars
rem   build-all-versions.bat 26.2 1.21.11  gradlew buildJar-26.2 buildJar-1.21.11
setlocal
cd /d "%~dp0"
if "%~1"=="" (
    call "%~dp0gradlew.bat" buildAllJars
    exit /b %ERRORLEVEL%
)
set TASKS=
:next
if "%~1"=="" goto run
set TASKS=%TASKS% buildJar-%~1
shift
goto next
:run
call "%~dp0gradlew.bat" %TASKS%
exit /b %ERRORLEVEL%
