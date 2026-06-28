@echo off
setlocal

set "SKILL_DIR=%~dp0"
if "%SKILL_DIR:~-1%"=="\" set "SKILL_DIR=%SKILL_DIR:~0,-1%"

if "%REF_REPOS_PROJECT_ROOT%"=="" (
  for /f "delims=" %%i in ('git rev-parse --show-toplevel 2^>nul') do set "REF_REPOS_PROJECT_ROOT=%%i"
)

if "%REF_REPOS_PROJECT_ROOT%"=="" (
  echo Not inside a git repository. Set REF_REPOS_PROJECT_ROOT to the target project. 1>&2
  exit /b 1
)

cd /d "%SKILL_DIR%"
call mill.bat scripts/ref-repos.scala %*
