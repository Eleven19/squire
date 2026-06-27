#!/usr/bin/env sh
# Enable git symlink materialization for this clone, then restore the skill
# symlinks (.agents/.claude/.cursor/.devin -> the canonical skill dir).
#
# Needed on Windows or any clone where core.symlinks was auto-detected false,
# which leaves the symlink files as plain text holding their target path.
set -e

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$ROOT" ]; then
  echo "Not inside a git repository." >&2
  exit 1
fi
cd "$ROOT"

git config core.symlinks true
# Re-materialize anything stored as a symlink blob (mode 120000).
git checkout -- .agents .claude .cursor .devin 2>/dev/null || git checkout -- .

echo "core.symlinks=true set; skill symlinks restored."
