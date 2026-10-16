@echo off
REM FlowLens launcher (Windows) - browser mode.
REM Builds the engine if needed (Maven is downloaded by the wrapper), then starts the local viewer server.
REM For the installable desktop app, see desktop\README.md.
setlocal
cd /d "%~dp0"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=7070"
set "JAR=analyzer\target\flowlens-analyzer.jar"

if not exist "%JAR%" (
  echo Building FlowLens ^(first run^)...
  pushd analyzer
  call mvnw.cmd -q -B -DskipTests package
  popd
)

java -jar "%JAR%" --serve %PORT% --open
endlocal
