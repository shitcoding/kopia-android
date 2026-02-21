#!/bin/bash
# Stop and remove mock storage backend containers for E2E testing
#
# Usage: ./stop_storage_backends.sh
set -e

echo "Stopping storage backends..."

docker rm -f kopia-e2e-minio kopia-e2e-webdav kopia-e2e-sftp 2>/dev/null || true

echo "Storage backends stopped and removed."
