#!/usr/bin/env bash
# Runs the opt-in real-provider S3 tests against Backblaze B2.
#
# Credentials are piped straight from a secret store into the test JVM's environment. They are never
# echoed, never written to a file, and never passed as command-line arguments (which would be visible
# in the process table and in shell history).
#
# They ARE in the environment of the Gradle/test JVM, which another process running as the same user
# could read, so the run uses --no-daemon: the secret's lifetime ends with the run instead of living
# on in a background daemon.
#
# Configure the non-secret parts here or in the environment; the secrets come from `pass`:
#   KOPIA_B2_BUCKET / KOPIA_B2_ENDPOINT / KOPIA_B2_REGION  - bucket coordinates
#   B2_PASS_ENTRY         - pass entry holding the application key (secret) on line 1
#   B2_PASS_KEY_ID_ENTRY  - pass entry holding the key id; if absent, a "keyID: <id>" metadata line
#                           in B2_PASS_ENTRY is used instead
#
# Usage: e2e/b2/run_b2_tests.sh [extra gradle args...]
set -euo pipefail

cd "$(dirname "$0")/../.."

PASS_ENTRY="${B2_PASS_ENTRY:-coding/kopia-kt/backblaze-app-key}"
PASS_KEY_ID_ENTRY="${B2_PASS_KEY_ID_ENTRY:-coding/kopia-kt/backblaze-app-key-id}"

# Bucket coordinates come from the environment or an untracked .env.local — never hardcoded, so the
# repo carries no one's real bucket name (B2 bucket names are globally unique).
if [ -f "$(dirname "$0")/.env.local" ]; then
    # shellcheck disable=SC1091
    set -a; . "$(dirname "$0")/.env.local"; set +a
fi

: "${KOPIA_B2_BUCKET:?set KOPIA_B2_BUCKET (or create e2e/b2/.env.local — see README)}"
: "${KOPIA_B2_ENDPOINT:?set KOPIA_B2_ENDPOINT, e.g. s3.<region>.backblazeb2.com}"
: "${KOPIA_B2_REGION:?set KOPIA_B2_REGION, e.g. us-east-005}"
export KOPIA_B2_BUCKET KOPIA_B2_ENDPOINT KOPIA_B2_REGION

if ! command -v pass >/dev/null; then
    echo "error: 'pass' not found. Export KOPIA_B2_KEY_ID and KOPIA_B2_APP_KEY yourself instead." >&2
    exit 1
fi

# One decryption, held only in this shell's memory. `set +x` guards against a caller running with
# tracing on, which would otherwise print the secret.
set +x
entry="$(pass show "$PASS_ENTRY")"
KOPIA_B2_APP_KEY="$(printf '%s\n' "$entry" | head -n 1)"

# Preferred: the key id in its own entry. Fallback: a metadata line inside the main entry.
if KOPIA_B2_KEY_ID="$(pass show "$PASS_KEY_ID_ENTRY" 2>/dev/null | head -n 1)" && [ -n "$KOPIA_B2_KEY_ID" ]; then
    :
else
    KOPIA_B2_KEY_ID="$(printf '%s\n' "$entry" | awk -F'[:=][[:space:]]*' 'tolower($1) ~ /keyid|applicationkeyid|key_id/ {print $2; exit}')"
fi
unset entry
export KOPIA_B2_APP_KEY KOPIA_B2_KEY_ID

if [ -z "$KOPIA_B2_APP_KEY" ] || [ -z "$KOPIA_B2_KEY_ID" ]; then
    echo "error: could not read both halves of the credential from pass entry '$PASS_ENTRY'." >&2
    echo "       Expected the application key on line 1 of '$PASS_ENTRY', and the key id either" >&2
    echo "       in '$PASS_KEY_ID_ENTRY' or as a 'keyID: <id>' line in the main entry." >&2
    exit 1
fi

echo "==> Running B2 provider tests against bucket '$KOPIA_B2_BUCKET' ($KOPIA_B2_REGION)"
echo "    key id: ${KOPIA_B2_KEY_ID:0:4}…${KOPIA_B2_KEY_ID: -3}  (redacted)"

# Gradle inherits the exported environment; nothing secret appears in the command line.
# --no-daemon:    do not leave a long-lived JVM holding these credentials in its environment.
# --rerun-tasks:  environment variables are not Gradle task inputs, so an unchanged working tree
#                 would mark :storage:test UP-TO-DATE and report success having run NOTHING —
#                 a false pass exactly when you re-run to check a new key or bucket.
./gradlew --no-daemon --rerun-tasks :storage:test --tests '*B2Provider*' "$@"
