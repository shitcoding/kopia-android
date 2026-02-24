#!/usr/bin/env bash
#
# Silent test runner for KopiaKt.
# Runs Gradle test tasks silently — only prints output for FAILED tests.
# Keeps CI output clean while preserving full failure diagnostics.
#
# Usage:
#   ./scripts/run_tests.sh                    # Run core + snapshot tests
#   ./scripts/run_tests.sh core               # Run only core tests
#   ./scripts/run_tests.sh snapshot            # Run only snapshot tests
#   ./scripts/run_tests.sh core snapshot       # Run core and snapshot tests
#   ./scripts/run_tests.sh all                 # Run all modules (core, snapshot, storage, android, app)
#   ./scripts/run_tests.sh --class "org.kopiaKt.core.pack.PackIndexV2Test"  # Single test class
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Temp files tracked for cleanup
TEMP_FILES=()

cleanup() {
    for f in "${TEMP_FILES[@]+"${TEMP_FILES[@]}"}"; do
        rm -f "$f"
    done
}
trap cleanup EXIT INT TERM

# Run a command silently. Print output only on failure.
# Returns the command's actual exit code.
run_silent() {
    local description="$1"
    shift
    local tmp_file
    tmp_file=$(mktemp)
    TEMP_FILES+=("$tmp_file")

    if "$@" > "$tmp_file" 2>&1; then
        printf "  \033[32m✓\033[0m %s\n" "$description"
        rm -f "$tmp_file"
        return 0
    else
        local exit_code=$?
        printf "  \033[31m✗\033[0m %s (exit %d)\n" "$description" "$exit_code"
        echo "--- Output ---"
        cat "$tmp_file"
        echo "--- End ---"
        rm -f "$tmp_file"
        return "$exit_code"
    fi
}

# Default modules if none specified
DEFAULT_MODULES="core snapshot"
ALL_MODULES="core snapshot storage android app"

modules=()
test_class=""
gradle_args=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --class)
            test_class="$2"
            shift 2
            ;;
        --info|--debug|--stacktrace)
            gradle_args+=("$1")
            shift
            ;;
        all)
            modules=($ALL_MODULES)
            shift
            ;;
        core|snapshot|storage|android|app|e2e)
            modules+=("$1")
            shift
            ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $0 [core|snapshot|storage|android|app|e2e|all] [--class <fully.qualified.TestClass>]"
            exit 1
            ;;
    esac
done

# Default to core + snapshot if nothing specified
if [[ ${#modules[@]} -eq 0 ]]; then
    modules=($DEFAULT_MODULES)
fi

echo "Running tests for: ${modules[*]}"
echo ""

passed=0
failed=0
failed_modules=()

for module in "${modules[@]}"; do
    task=":${module}:test"
    desc="${module} tests"

    cmd=("$PROJECT_DIR/gradlew" "-p" "$PROJECT_DIR" "$task" "--console=plain" "--no-daemon")

    if [[ -n "$test_class" ]]; then
        cmd+=("--tests" "$test_class")
        desc="${module} tests (${test_class##*.})"
    fi

    if [[ ${#gradle_args[@]} -gt 0 ]]; then
        cmd+=("${gradle_args[@]}")
    fi

    if run_silent "$desc" "${cmd[@]}"; then
        ((passed++))
    else
        ((failed++))
        failed_modules+=("$module")
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ $failed -eq 0 ]]; then
    printf "\033[32mAll %d module(s) passed.\033[0m\n" "$passed"
else
    printf "\033[31m%d failed\033[0m, %d passed (failed: %s)\n" \
        "$failed" "$passed" "${failed_modules[*]}"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit "$failed"
