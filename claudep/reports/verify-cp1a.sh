#!/usr/bin/env bash
#
# Reproducible verification for Gate CP1-A (local Provider skeleton + fake Gateway).
#
# Why this script exists: CP1-A was implemented on a machine with no Android SDK, so the Gradle
# compile and unit-test steps could not be run locally. Every command below is the command that
# must be run somewhere the SDK is present — a developer machine or the CI environment — before
# CP1-A can be called verified.
#
# It is written to match the existing GitHub Actions environment: JDK 17, the Android SDK
# provisioned by the runner, and the same Gradle wrapper the workflows use.
#
# Usage:
#   ./claudep/reports/verify-cp1a.sh            # focused: only the Claude P surface
#   ./claudep/reports/verify-cp1a.sh --full     # focused, plus the whole :ai and :app suites
#
# Exit code is non-zero if any step fails. Each step prints its own PASS/FAIL banner so a partial
# run is never mistaken for a clean one.

set -uo pipefail

FULL=0
[[ "${1:-}" == "--full" ]] && FULL=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

FAILURES=0

step() {
  local name="$1"
  shift
  echo
  echo "=============================================================="
  echo "STEP: $name"
  echo "CMD : $*"
  echo "=============================================================="
  if "$@"; then
    echo "PASS: $name"
  else
    local code=$?
    echo "FAIL: $name (exit $code)"
    FAILURES=$((FAILURES + 1))
  fi
}

require_sdk() {
  if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
    echo "ERROR: no Android SDK found."
    echo "       Set ANDROID_HOME, or create local.properties with sdk.dir=<path>."
    echo "       Without it Gradle fails at configuration time and NO step below is meaningful."
    exit 2
  fi
}

require_sdk

chmod +x gradlew

# ---------------------------------------------------------------------------------------------
# 0. Repository hygiene
# ---------------------------------------------------------------------------------------------
step "git diff --check (whitespace errors)" git diff --check

# ---------------------------------------------------------------------------------------------
# 1. Compilation. :app depends on :ai, so this compiles both, including the new Claude P sources.
# ---------------------------------------------------------------------------------------------
step "compile :ai (debug)" ./gradlew :ai:compileDebugKotlin --console=plain
step "compile :app (debug)" ./gradlew :app:compileDebugKotlin --console=plain

# ---------------------------------------------------------------------------------------------
# 2. Claude P focused tests.
#
# NOTE: :ai unit tests are NOT run by any existing workflow, and :app:testDebugUnitTest uses a
# pinned --tests allowlist. These two steps are what actually execute the CP1-A tests.
# ---------------------------------------------------------------------------------------------
step "Claude P protocol + gateway tests (:ai)" \
  ./gradlew :ai:testDebugUnitTest --console=plain \
    --tests "me.rerere.ai.provider.claudep.*" \
    --tests "me.rerere.ai.provider.providers.ClaudePProviderStreamTest" \
    --tests "me.rerere.ai.provider.providers.ClaudePProviderCancellationTest" \
    --tests "me.rerere.ai.provider.ClaudePSettingTest" \
    --tests "me.rerere.ai.provider.ProviderManagerClaudePTest"

step "Claude P provider-settings + background-exclusion tests (:app)" \
  ./gradlew :app:testDebugUnitTest --console=plain \
    --tests "me.rerere.rikkahub.data.ai.background.ClaudePBackgroundExclusionTest" \
    --tests "me.rerere.rikkahub.ui.pages.setting.components.ClaudePProviderConfigureTest"

# ---------------------------------------------------------------------------------------------
# 2b. A pinned --tests filter fails only when the COMBINED filter matches nothing, and a single
#     pattern matching no class is silent. Gradle's test logging is off project-wide, so listing
#     the produced JUnit XML is the only proof that a class actually ran rather than compiled.
# ---------------------------------------------------------------------------------------------
step "report executed Claude P test classes" bash -c '
  found=0
  for results in ai/build/test-results/testDebugUnitTest app/build/test-results/testDebugUnitTest; do
    [ -d "$results" ] || continue
    while read -r xml; do
      [ -n "$xml" ] || continue
      found=$((found + 1))
      echo "  executed: $(basename "$xml" .xml)"
    done < <(find "$results" -name "*ClaudeP*.xml" | sort)
  done
  if [ "$found" -eq 0 ]; then
    echo "  ERROR: no Claude P test result XML found -- the tests compiled but did not run."
    exit 1
  fi
  echo "  total Claude P test classes executed: $found"
'

# ---------------------------------------------------------------------------------------------
# 3. Full suites, for regressions in the areas the change touches.
# ---------------------------------------------------------------------------------------------
if [[ "$FULL" -eq 1 ]]; then
  step "full :ai unit tests" ./gradlew :ai:testDebugUnitTest --console=plain
  step "full :app unit tests" ./gradlew :app:testDebugUnitTest --console=plain
fi

# ---------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------
echo
echo "=============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "RESULT: all executed steps passed."
  echo
  echo "Reminder: this proves compilation, parsing, provider semantics and the fail-closed"
  echo "wiring. It does NOT prove anything about a real Gateway, a real Claude Code runtime,"
  echo "device pairing or the WSS transport -- none of those exist in CP1-A."
else
  echo "RESULT: $FAILURES step(s) FAILED. This run is NOT a pass."
fi
echo "=============================================================="
exit $((FAILURES == 0 ? 0 : 1))
