#!/bin/bash
# Seed storage backends with Kopia repositories for E2E testing.
#
# Creates a Kopia repository on each backend (S3/MinIO, WebDAV, SFTP) with a
# minimal snapshot so the Android app can connect and see real data.
#
# Prerequisites:
#   - Docker storage backends running (start_storage_backends.sh)
#   - kopia CLI installed (brew install kopia)
#
# Usage: ./seed_storage_backends.sh
set -e

REPO_PASSWORD="test123"

# Use a temporary config file to avoid interfering with user's kopia config
KOPIA_CONFIG=$(mktemp -d)/repository.config
export KOPIA_CONFIG_PATH="$KOPIA_CONFIG"

echo "Seeding storage backends with Kopia repositories..."
echo "  Repository password: $REPO_PASSWORD"
echo "  Config file: $KOPIA_CONFIG"
echo ""

# ========================================
# Prerequisite check
# ========================================
if ! command -v kopia &>/dev/null; then
  echo "ERROR: kopia CLI not found."
  echo "Install with: brew install kopia"
  exit 1
fi

echo "Using kopia $(kopia --version 2>&1 | head -1)"
echo ""

# Helper: create a temp directory with a small test file, create a snapshot, disconnect
create_snapshot_and_disconnect() {
  local LABEL="$1"
  TEMP_DIR=$(mktemp -d)
  echo "test content for $LABEL backend" > "$TEMP_DIR/test.txt"
  mkdir -p "$TEMP_DIR/subdir"
  echo "nested file" > "$TEMP_DIR/subdir/nested.txt"

  echo "  Creating snapshot from $TEMP_DIR..."
  KOPIA_PASSWORD="$REPO_PASSWORD" kopia snapshot create "$TEMP_DIR" \
    --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /'

  echo "  Disconnecting..."
  kopia repository disconnect --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /' || true

  rm -rf "$TEMP_DIR"
  echo "  Done."
  echo ""
}

# ========================================
# 1. S3 (MinIO) - Create bucket and repository
# ========================================
echo "=== S3 (MinIO) ==="

# Create the S3 bucket using kopia's built-in support (it creates the bucket if
# needed when using --endpoint). However, kopia does NOT auto-create buckets on
# MinIO. We use curl to MinIO's API to create it.
echo "  Creating bucket 'kopia-e2e' via MinIO API..."
# MinIO supports the S3 CreateBucket API
curl -sf -X PUT \
  -H "Authorization: AWS4-HMAC-SHA256" \
  "http://localhost:9000/kopia-e2e" \
  -u "kopia:kopia123456" \
  2>/dev/null || true

# Alternative: use the MinIO container's built-in mc client
docker exec kopia-e2e-minio mc alias set local http://localhost:9000 kopia kopia123456 2>&1 | sed 's/^/    /' || true
docker exec kopia-e2e-minio mc mb --ignore-existing local/kopia-e2e 2>&1 | sed 's/^/    /'

echo "  Creating S3 repository..."
KOPIA_PASSWORD="$REPO_PASSWORD" kopia repository create s3 \
  --bucket=kopia-e2e \
  --endpoint=localhost:9000 \
  --access-key=kopia \
  --secret-access-key=kopia123456 \
  --disable-tls \
  --config-file="$KOPIA_CONFIG" \
  2>&1 | sed 's/^/    /'

create_snapshot_and_disconnect "S3"

# ========================================
# 2. WebDAV - Create repository
# ========================================
echo "=== WebDAV ==="

echo "  Creating WebDAV repository..."
KOPIA_PASSWORD="$REPO_PASSWORD" kopia repository create webdav \
  --url=http://localhost:8080/ \
  --webdav-username=kopia \
  --webdav-password=kopia123 \
  --flat \
  --config-file="$KOPIA_CONFIG" \
  2>&1 | sed 's/^/    /'

create_snapshot_and_disconnect "WebDAV"

# ========================================
# 3. SFTP - Create directory and repository
# ========================================
echo "=== SFTP ==="

# Create the target directory inside the SFTP container
echo "  Creating SFTP directory structure..."
docker exec kopia-e2e-sftp mkdir -p /home/kopia/upload/kopia-e2e 2>/dev/null || true
docker exec kopia-e2e-sftp chown -R kopia:users /home/kopia/upload 2>/dev/null || true

echo "  Fetching SFTP host key..."
SFTP_KNOWN_HOSTS=$(mktemp)
ssh-keyscan -p 2222 localhost 2>/dev/null | grep -v '^#' > "$SFTP_KNOWN_HOSTS"

echo "  Creating SFTP repository..."
KOPIA_PASSWORD="$REPO_PASSWORD" kopia repository create sftp \
  --path=/upload/kopia-e2e \
  --host=localhost \
  --port=2222 \
  --username=kopia \
  --sftp-password=kopia123 \
  --known-hosts="$SFTP_KNOWN_HOSTS" \
  --flat \
  --config-file="$KOPIA_CONFIG" \
  2>&1 | sed 's/^/    /'

create_snapshot_and_disconnect "SFTP"

# ========================================
# Verification
# ========================================
echo "=== Verification ==="

echo "  Verifying S3 repository is connectable..."
KOPIA_PASSWORD="$REPO_PASSWORD" kopia repository connect s3 \
  --bucket=kopia-e2e \
  --endpoint=localhost:9000 \
  --access-key=kopia \
  --secret-access-key=kopia123456 \
  --disable-tls \
  --config-file="$KOPIA_CONFIG" \
  2>&1 | sed 's/^/    /'
KOPIA_PASSWORD="$REPO_PASSWORD" kopia snapshot list --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /'
kopia repository disconnect --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /' || true
echo "  S3 OK"
echo ""

echo "  Verifying WebDAV repository is connectable..."
KOPIA_PASSWORD="$REPO_PASSWORD" kopia repository connect webdav \
  --url=http://localhost:8080/ \
  --webdav-username=kopia \
  --webdav-password=kopia123 \
  --flat \
  --config-file="$KOPIA_CONFIG" \
  2>&1 | sed 's/^/    /'
KOPIA_PASSWORD="$REPO_PASSWORD" kopia snapshot list --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /'
kopia repository disconnect --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /' || true
echo "  WebDAV OK"
echo ""

echo "  Verifying SFTP repository is connectable..."
KOPIA_PASSWORD="$REPO_PASSWORD" kopia repository connect sftp \
  --path=/upload/kopia-e2e \
  --host=localhost \
  --port=2222 \
  --username=kopia \
  --sftp-password=kopia123 \
  --known-hosts="$SFTP_KNOWN_HOSTS" \
  --flat \
  --config-file="$KOPIA_CONFIG" \
  2>&1 | sed 's/^/    /'
KOPIA_PASSWORD="$REPO_PASSWORD" kopia snapshot list --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /'
kopia repository disconnect --config-file="$KOPIA_CONFIG" 2>&1 | sed 's/^/    /' || true
echo "  SFTP OK"
echo ""

# Cleanup temp files
rm -f "$SFTP_KNOWN_HOSTS"
rm -rf "$(dirname "$KOPIA_CONFIG")"

echo "All backends seeded and verified successfully."
echo ""
echo "Repositories are ready for E2E tests:"
echo "  S3:     bucket=kopia-e2e, endpoint=http://10.0.2.2:9000"
echo "  WebDAV: url=http://10.0.2.2:8080/"
echo "  SFTP:   host=10.0.2.2:2222, path=/upload/kopia-e2e"
echo "  Password: $REPO_PASSWORD"
