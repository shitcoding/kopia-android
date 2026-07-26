#!/usr/bin/env bash
# Collects the trust material the app needs to reach this profile SECURELY, and prints it as the
# environment block the opt-in HomelabBackendTest reads.
#
# ⚠ THE OUTPUT CONTAINS CREDENTIALS (the generated SFTP/WebDAV/MinIO passwords) alongside the public
# trust material. Do not paste it into a shared channel, a ticket, or a commit. Pipe it to a file you
# then `source`, or run `eval "$(scripts/trust_material.sh)"`.
#
# Usage: trust_material.sh [ssh-host] [test-host-address]
set -euo pipefail

cd "$(dirname "$0")/.."

SSH_HOST="${1:-${KOPIA_E2E_SSH:?set KOPIA_E2E_SSH or pass the ssh host as the first argument}}"
TEST_HOST="${2:-${KOPIA_E2E_HOST:?set KOPIA_E2E_HOST or pass the test host address as the second argument}}"
SFTP_PORT="${SFTP_PORT:-2222}"

if [ ! -f certs/server.crt ]; then
    echo "error: certs/server.crt missing — run scripts/gen_certs.sh first" >&2
    exit 1
fi
if [ ! -f .env.local ]; then
    echo "error: .env.local missing — run scripts/deploy.sh first (it generates the credentials)" >&2
    exit 1
fi
# Read the credentials that were ACTUALLY deployed, rather than re-guessing defaults.
# shellcheck disable=SC1091
set -a; . ./.env.local; set +a

# --- SFTP host keys: the SECURE path this profile exists for -------------------------------------
# Read the public host keys from the container itself rather than ssh-keyscan'ing over the network:
# scanning trusts whatever answers, which is exactly the trust-on-first-use weakness being tested
# against. Emit every host key the server offers, so the pin holds whichever algorithm is negotiated.
known_hosts="$(
    ssh "$SSH_HOST" 'for f in /dev/null $(docker exec kopia-e2e-sftp sh -c "ls /etc/ssh/ssh_host_*_key.pub"); do
        [ "$f" = /dev/null ] && continue
        docker exec kopia-e2e-sftp cat "$f"
    done' | awk -v hp="[$TEST_HOST]:$SFTP_PORT" 'NF >= 2 {print hp, $1, $2}'
)"

if [ -z "$known_hosts" ]; then
    echo "error: could not read any SFTP host key from kopia-e2e-sftp (is the profile deployed?)" >&2
    exit 1
fi

# --- TLS material -------------------------------------------------------------------------------
cert_sha256="$(openssl x509 -in certs/server.crt -noout -fingerprint -sha256 |
    sed 's/.*=//' | tr -d ':' | tr 'A-Z' 'a-z')"

cat <<EOF
# ---------------------------------------------------------------------------------------------
# Trust material for the kopia-e2e homelab profile on $TEST_HOST
# CONTAINS CREDENTIALS — do not share. Usage:
#   eval "\$(e2e/homelab/scripts/trust_material.sh)"
#   ./gradlew :storage:test --tests '*HomelabBackendTest*'
# ---------------------------------------------------------------------------------------------
export KOPIA_HOMELAB_HOST='$TEST_HOST'

# SFTP — verified by a pinned host key (NOT the insecure "trust any key" opt-in)
export KOPIA_HOMELAB_SFTP_PORT='$SFTP_PORT'
export KOPIA_HOMELAB_SFTP_USER='kopia'
export KOPIA_HOMELAB_SFTP_PASSWORD='$SFTP_PASSWORD'
export KOPIA_HOMELAB_SFTP_KNOWN_HOSTS='$known_hosts'

# WebDAV over https — verified by pinning the server certificate (leaf SHA-256)
export KOPIA_HOMELAB_WEBDAV_URL='https://$TEST_HOST:8443/'
export KOPIA_HOMELAB_WEBDAV_USER='$WEBDAV_USERNAME'
export KOPIA_HOMELAB_WEBDAV_PASSWORD='$WEBDAV_PASSWORD'
export KOPIA_HOMELAB_WEBDAV_CERT_SHA256='$cert_sha256'

# S3 (MinIO) over https — verified by a custom root CA, hostname verification still ON
export KOPIA_HOMELAB_S3_ENDPOINT='https://$TEST_HOST:9000'
export KOPIA_HOMELAB_S3_BUCKET='${S3_BUCKET:-kopia-e2e}'
export KOPIA_HOMELAB_S3_ACCESS_KEY='$MINIO_ROOT_USER'
export KOPIA_HOMELAB_S3_SECRET_KEY='$MINIO_ROOT_PASSWORD'
export KOPIA_HOMELAB_S3_ROOT_CA_FILE='$PWD/certs/ca.crt'
EOF
