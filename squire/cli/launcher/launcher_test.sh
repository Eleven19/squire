#!/usr/bin/env bash
# launcher_test.sh — offline unit tests for the squire POSIX launcher
#
# Usage: bash squire/cli/launcher/launcher_test.sh
#
# No network access required — every invocation of the launcher is driven
# through SQUIRE_DOWNLOAD (a stub script) and SQUIRE_JVM / SQUIRE_VERSION.
# Tests verify post-conditions: which files appear in the cache and how many
# times the download stub was called.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAUNCHER="$SCRIPT_DIR/squire"

PASS=0
FAIL=0
pass() { printf 'PASS: %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf 'FAIL: %s\n' "$1"; FAIL=$((FAIL + 1)); }

# ---------------------------------------------------------------------------
# Shared temp tree
# ---------------------------------------------------------------------------

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

DEFAULT_VERSION="0.1.0"

# ---------------------------------------------------------------------------
# Download mock
#
# Called as: "$MOCK_DOWNLOAD" <url> <dest>
# Creates a minimal executable stub at <dest> and increments COUNTER_FILE.
# ---------------------------------------------------------------------------

COUNTER_FILE="$WORK_DIR/download_count"
MOCK_DOWNLOAD="$WORK_DIR/mock_download.sh"

cat > "$MOCK_DOWNLOAD" <<EOF
#!/bin/sh
url="\$1"
dest="\$2"
mkdir -p "\$(dirname "\$dest")"
# Write a minimal self-exiting stub so exec succeeds.
printf '#!/bin/sh\nprintf "stub\\\\n"\n' > "\$dest"
chmod +x "\$dest"
# Increment the shared download counter.
c=0
if [ -f "$COUNTER_FILE" ]; then c=\$(cat "$COUNTER_FILE"); fi
printf '%d' "\$((c + 1))" > "$COUNTER_FILE"
EOF
chmod +x "$MOCK_DOWNLOAD"

# ---------------------------------------------------------------------------
# Fake java (for SQUIRE_JVM jar-fallback tests)
# ---------------------------------------------------------------------------

FAKE_BIN_DIR="$WORK_DIR/fakebin"
mkdir -p "$FAKE_BIN_DIR"
cat > "$FAKE_BIN_DIR/java" <<'EOF'
#!/bin/sh
# Accepts "java -jar <file> [args...]" and exits 0.
printf 'java-jar-invoked\n'
exit 0
EOF
chmod +x "$FAKE_BIN_DIR/java"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

reset_counter() { rm -f "$COUNTER_FILE"; }
get_counter()   { [ -f "$COUNTER_FILE" ] && cat "$COUNTER_FILE" || printf '0'; }
new_cache()     { mktemp -d "$WORK_DIR/cache.XXXXXX"; }

# Run the launcher in a subshell; suppress output; never abort the test
# script on non-zero exit from the stub binary.
run_launcher() {
  bash "$LAUNCHER" "$@" >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------
# Test 1: SQUIRE_VERSION env wins over everything
# ---------------------------------------------------------------------------

cache=$(new_cache)
reset_counter

SQUIRE_VERSION="9.8.7" \
  SQUIRE_CACHE="$cache" \
  SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
  SQUIRE_BASE_URL="file://$WORK_DIR" \
  SQUIRE_JVM=1 \
  PATH="$FAKE_BIN_DIR:$PATH" \
  run_launcher

if [ -d "$cache/9.8.7" ]; then
  pass "version: SQUIRE_VERSION env used (9.8.7 cache dir created)"
else
  fail "version: SQUIRE_VERSION env not used (expected $cache/9.8.7 to exist)"
fi

# ---------------------------------------------------------------------------
# Test 2: .squire-version file is used when SQUIRE_VERSION is absent
# ---------------------------------------------------------------------------

cache=$(new_cache)
reset_counter
FILE_VERSION_DIR=$(mktemp -d "$WORK_DIR/vdir.XXXXXX")
printf '3.2.1' > "$FILE_VERSION_DIR/.squire-version"

(
  cd "$FILE_VERSION_DIR"
  SQUIRE_CACHE="$cache" \
    SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
    SQUIRE_BASE_URL="file://$WORK_DIR" \
    SQUIRE_JVM=1 \
    PATH="$FAKE_BIN_DIR:$PATH" \
    bash "$LAUNCHER" >/dev/null 2>&1 || true
)

if [ -d "$cache/3.2.1" ]; then
  pass "version: .squire-version file used (3.2.1 cache dir created)"
else
  fail "version: .squire-version not used (expected $cache/3.2.1 to exist)"
fi

# ---------------------------------------------------------------------------
# Test 3: default version used when neither env nor file is present
# ---------------------------------------------------------------------------

cache=$(new_cache)
reset_counter
NO_VERSION_DIR=$(mktemp -d "$WORK_DIR/novdir.XXXXXX")

(
  cd "$NO_VERSION_DIR"
  SQUIRE_CACHE="$cache" \
    SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
    SQUIRE_BASE_URL="file://$WORK_DIR" \
    SQUIRE_JVM=1 \
    PATH="$FAKE_BIN_DIR:$PATH" \
    bash "$LAUNCHER" >/dev/null 2>&1 || true
)

if [ -d "$cache/$DEFAULT_VERSION" ]; then
  pass "version: built-in default ($DEFAULT_VERSION) used when no version source present"
else
  fail "version: built-in default not used (expected $cache/$DEFAULT_VERSION to exist)"
fi

# ---------------------------------------------------------------------------
# Test 4: SQUIRE_VERSION beats .squire-version file
# ---------------------------------------------------------------------------

cache=$(new_cache)
reset_counter
MIXED_DIR=$(mktemp -d "$WORK_DIR/mdir.XXXXXX")
printf '3.2.1' > "$MIXED_DIR/.squire-version"

(
  cd "$MIXED_DIR"
  SQUIRE_VERSION="5.5.5" \
    SQUIRE_CACHE="$cache" \
    SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
    SQUIRE_BASE_URL="file://$WORK_DIR" \
    SQUIRE_JVM=1 \
    PATH="$FAKE_BIN_DIR:$PATH" \
    bash "$LAUNCHER" >/dev/null 2>&1 || true
)

env_used=false
file_used=false
[ -d "$cache/5.5.5" ] && env_used=true
[ -d "$cache/3.2.1" ] && file_used=true

if $env_used && ! $file_used; then
  pass "version: SQUIRE_VERSION wins over .squire-version (5.5.5 used, 3.2.1 ignored)"
else
  fail "version: precedence wrong (env_used=$env_used file_used=$file_used)"
fi

# ---------------------------------------------------------------------------
# Test 5: platform detection maps the current host to a supported key
# ---------------------------------------------------------------------------

uname_s=$(uname -s)
uname_m=$(uname -m)
case "$uname_s" in
  Darwin)  case "$uname_m" in arm64) expected_platform="macos-arm64" ;; *) expected_platform="" ;; esac ;;
  Linux)   case "$uname_m" in x86_64) expected_platform="linux-x64" ;; aarch64|arm64) expected_platform="linux-arm64" ;; *) expected_platform="" ;; esac ;;
  *)       expected_platform="" ;;
esac

if [ -n "$expected_platform" ]; then
  cache=$(new_cache)
  reset_counter
  SQUIRE_VERSION="$DEFAULT_VERSION" \
    SQUIRE_CACHE="$cache" \
    SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
    SQUIRE_BASE_URL="file://$WORK_DIR" \
    run_launcher

  if [ -f "$cache/$DEFAULT_VERSION/squire-${expected_platform}" ]; then
    pass "platform: $uname_s/$uname_m detected as $expected_platform"
  else
    fail "platform: native file for $expected_platform not in cache (got: $(ls "$cache/$DEFAULT_VERSION/" 2>/dev/null || echo nothing))"
  fi
else
  pass "platform: unsupported platform ($uname_s/$uname_m) — native unavailable, skipping native detection test"
fi

# ---------------------------------------------------------------------------
# Test 6: native binary preferred over JAR (SQUIRE_JVM unset)
# ---------------------------------------------------------------------------

if [ -n "$expected_platform" ]; then
  cache=$(new_cache)
  reset_counter
  SQUIRE_VERSION="$DEFAULT_VERSION" \
    SQUIRE_CACHE="$cache" \
    SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
    SQUIRE_BASE_URL="file://$WORK_DIR" \
    run_launcher

  native_file="$cache/$DEFAULT_VERSION/squire-${expected_platform}"
  jar_file="$cache/$DEFAULT_VERSION/squire-assembly.jar"
  if [ -f "$native_file" ] && [ ! -f "$jar_file" ]; then
    pass "native-preferred: native binary selected; assembly JAR not downloaded"
  else
    fail "native-preferred: wrong selection (native=$([ -f "$native_file" ] && echo yes || echo no) jar=$([ -f "$jar_file" ] && echo yes || echo no))"
  fi
else
  pass "native-preferred: skipped (platform not supported, no native binary expected)"
fi

# ---------------------------------------------------------------------------
# Test 7: SQUIRE_JVM forces the assembly JAR path
# ---------------------------------------------------------------------------

cache=$(new_cache)
reset_counter
SQUIRE_VERSION="$DEFAULT_VERSION" \
  SQUIRE_CACHE="$cache" \
  SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD" \
  SQUIRE_BASE_URL="file://$WORK_DIR" \
  SQUIRE_JVM=1 \
  PATH="$FAKE_BIN_DIR:$PATH" \
  run_launcher

jar_file="$cache/$DEFAULT_VERSION/squire-assembly.jar"
if [ -f "$jar_file" ]; then
  pass "jar-fallback: SQUIRE_JVM=1 forces assembly JAR download"
else
  fail "jar-fallback: assembly JAR not cached when SQUIRE_JVM=1"
fi

# ---------------------------------------------------------------------------
# Test 8: caching — second invocation skips re-download
# ---------------------------------------------------------------------------

cache=$(new_cache)
reset_counter

common=(
  SQUIRE_VERSION="$DEFAULT_VERSION"
  SQUIRE_CACHE="$cache"
  SQUIRE_DOWNLOAD="$MOCK_DOWNLOAD"
  SQUIRE_BASE_URL="file://$WORK_DIR"
  SQUIRE_JVM=1
  PATH="$FAKE_BIN_DIR:$PATH"
)

# First call: artifact not cached yet — download mock runs.
env "${common[@]}" bash "$LAUNCHER" >/dev/null 2>&1 || true
count_first=$(get_counter)

# Second call: artifact already in cache — download mock must NOT run again.
env "${common[@]}" bash "$LAUNCHER" >/dev/null 2>&1 || true
count_second=$(get_counter)

if [ "$count_first" = "1" ] && [ "$count_second" = "1" ]; then
  pass "caching: artifact downloaded once; second invocation used cache (count=1)"
else
  fail "caching: expected count=1 after both runs, got first=$count_first second=$count_second"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
