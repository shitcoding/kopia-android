#!/bin/bash
# Start mock storage backends for E2E testing
# Starts Docker containers for MinIO (S3), WebDAV, and SFTP
# with fixed ports and credentials matching the E2E test suite.
#
# Ports:
#   MinIO (S3):  9000 (API), 9001 (console)
#   WebDAV:      8080
#   SFTP:        2222
#
# Usage: ./start_storage_backends.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Starting storage backends for E2E testing..."

# Stop any existing containers first (idempotent)
"$SCRIPT_DIR/stop_storage_backends.sh" 2>/dev/null || true

# ========================================
# MinIO (S3-compatible object storage)
# ========================================
echo "Starting MinIO..."
docker run -d --name kopia-e2e-minio \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=kopia \
  -e MINIO_ROOT_PASSWORD=kopia123456 \
  minio/minio:latest server /data --console-address ":9001"

# ========================================
# WebDAV
# ========================================
echo "Starting WebDAV..."
docker run -d --name kopia-e2e-webdav \
  -p 8080:80 \
  -e AUTH_TYPE=Basic \
  -e USERNAME=kopia \
  -e PASSWORD=kopia123 \
  bytemark/webdav:2.4

# ========================================
# SFTP
# ========================================
# Format: user:password:::directory
# Creates user "kopia" with password "kopia123" and default dir "upload"
echo "Starting SFTP..."
docker run -d --name kopia-e2e-sftp \
  -p 2222:22 \
  atmoz/sftp:latest "kopia:kopia123:::upload"

# ========================================
# Health checks with retries
# ========================================
echo ""
echo "Waiting for backends to be ready..."

# MinIO health check (up to 30 seconds)
MINIO_READY=false
for i in $(seq 1 30); do
  if curl -sf http://localhost:9000/minio/health/ready >/dev/null 2>&1; then
    echo "  MinIO ready (${i}s)"
    MINIO_READY=true
    break
  fi
  sleep 1
done
if [ "$MINIO_READY" = false ]; then
  echo "  ERROR: MinIO did not become ready within 30s"
  docker logs kopia-e2e-minio 2>&1 | tail -5
  exit 1
fi

# WebDAV health check (up to 15 seconds)
# Expects HTTP 401 (auth required) as sign the server is up
WEBDAV_READY=false
for i in $(seq 1 15); do
  HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "200" ]; then
    echo "  WebDAV ready (${i}s)"
    WEBDAV_READY=true
    break
  fi
  sleep 1
done
if [ "$WEBDAV_READY" = false ]; then
  echo "  ERROR: WebDAV did not become ready within 15s"
  docker logs kopia-e2e-webdav 2>&1 | tail -5
  exit 1
fi

# SFTP health check (up to 15 seconds)
SFTP_READY=false
for i in $(seq 1 15); do
  if nc -z localhost 2222 2>/dev/null; then
    echo "  SFTP ready (${i}s)"
    SFTP_READY=true
    break
  fi
  sleep 1
done
if [ "$SFTP_READY" = false ]; then
  echo "  ERROR: SFTP did not become ready within 15s"
  docker logs kopia-e2e-sftp 2>&1 | tail -5
  exit 1
fi

echo ""
echo "All storage backends are running and healthy."
echo "  MinIO (S3): http://localhost:9000  (user: kopia, pass: kopia123456)"
echo "  WebDAV:     http://localhost:8080  (user: kopia, pass: kopia123)"
echo "  SFTP:       localhost:2222         (user: kopia, pass: kopia123)"
