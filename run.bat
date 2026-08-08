@echo off
REM FlowLens launcher (Windows).
REM Builds the analyzer if needed, then starts the local viewer server.
setlocal
cd /d "%~dp0"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=7070"
set "JAR=analyzer\target\flowlens-analyzer.jar"

if not exist "%JAR%" (
  echo Building FlowLens ^(first run^)...
  pushd analyzer
  call mvn -q -B package
  popd
)

echo Starting FlowLens on http://localhost:%PORT%
echo Open that URL, then click "Open project" or paste a project path.
java -jar "%JAR%" --serve %PORT%
endlocal
