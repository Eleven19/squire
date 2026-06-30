@echo off
setlocal enabledelayedexpansion

rem squire bootstrap launcher (Windows CMD)
rem
rem Version precedence: SQUIRE_VERSION > .squire-version (walk up) > built-in default
rem Prefers windows-x64 native .exe; set SQUIRE_JVM to any value to force the
rem assembly JAR path via java -jar.
rem
rem Env vars (all optional):
rem   SQUIRE_VERSION    pin a specific version
rem   SQUIRE_JVM        any non-empty value forces java -jar path
rem   SQUIRE_BASE_URL   Maven base URL
rem                     (default: https://repo1.maven.org/maven2/io/eleven19/squire-cli_3)

set "SQUIRE_DEFAULT_VERSION=0.1.0"
if not defined SQUIRE_BASE_URL (
  set "SQUIRE_BASE_URL=https://repo1.maven.org/maven2/io/eleven19/squire-cli_3"
)

rem -------------------------------------------------------------------------
rem Version resolution
rem -------------------------------------------------------------------------

if defined SQUIRE_VERSION (
  set "version=!SQUIRE_VERSION!"
  goto :version_done
)

rem Walk up from %CD% looking for the first .squire-version file.
set "search_dir=%CD%"
:version_walk
if exist "!search_dir!\.squire-version" (
  set /p version=<"!search_dir!\.squire-version"
  rem Trim leading spaces (set /p can include them).
  for /f "tokens=* delims= " %%v in ("!version!") do set "version=%%v"
  goto :version_done
)
rem Move to parent directory.
for %%p in ("!search_dir!") do set "parent=%%~dpp"
rem Strip trailing backslash so the equality check below works.
if "!parent:~-1!"=="\" set "parent=!parent:~0,-1!"
rem Stop when we reach the filesystem root (parent equals search_dir).
if "!parent!"=="!search_dir!" (
  set "version=%SQUIRE_DEFAULT_VERSION%"
  goto :version_done
)
set "search_dir=!parent!"
goto :version_walk

:version_done

rem -------------------------------------------------------------------------
rem Cache directory
rem -------------------------------------------------------------------------

if not defined LOCALAPPDATA set "LOCALAPPDATA=%USERPROFILE%\AppData\Local"
set "cache_dir=%LOCALAPPDATA%\squire\!version!"
if not exist "!cache_dir!" mkdir "!cache_dir!" 2>nul

set "artifact_base=!SQUIRE_BASE_URL!/!version!/squire-cli_3-!version!"

rem -------------------------------------------------------------------------
rem Artifact selection and launch
rem -------------------------------------------------------------------------

if defined SQUIRE_JVM goto :jar_fallback

rem Prefer the windows-x64 native binary.
set "cache_exe=!cache_dir!\squire-windows-x64.exe"
if not exist "!cache_exe!" (
  curl -fsSL -o "!cache_exe!" "!artifact_base!-windows-x64.exe"
  if errorlevel 1 (
    echo squire: error: download failed for windows-x64 native binary >&2
    del /f /q "!cache_exe!" 2>nul
    exit /b 1
  )
)
"!cache_exe!" %*
exit /b %errorlevel%

:jar_fallback
where java >nul 2>&1
if errorlevel 1 (
  echo squire: error: java not found on PATH >&2
  echo   Install a JRE 11+ or unset SQUIRE_JVM to use the native binary. >&2
  exit /b 1
)
set "cache_jar=!cache_dir!\squire-assembly.jar"
if not exist "!cache_jar!" (
  curl -fsSL -o "!cache_jar!" "!artifact_base!-assembly.jar"
  if errorlevel 1 (
    echo squire: error: download failed for assembly JAR >&2
    del /f /q "!cache_jar!" 2>nul
    exit /b 1
  )
)
java -jar "!cache_jar!" %*
exit /b %errorlevel%
