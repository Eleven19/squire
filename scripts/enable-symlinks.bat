@echo off
REM Enable git symlink materialization for this clone, then restore the skill
REM symlinks (.agents/.claude/.cursor/.devin -> the canonical skill dir).
REM
REM On Windows, symlinks also require either Developer Mode enabled or running
REM this from an elevated (admin) shell, plus git installed with symlink support.
setlocal
for /f "delims=" %%i in ('git rev-parse --show-toplevel 2^>nul') do set ROOT=%%i
if "%ROOT%"=="" (
  echo Not inside a git repository.
  exit /b 1
)
cd /d "%ROOT%"

git config core.symlinks true
git checkout -- .agents .claude .cursor .devin 2>nul || git checkout -- .

echo core.symlinks=true set; skill symlinks restored.
endlocal
