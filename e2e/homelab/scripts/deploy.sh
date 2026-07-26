#!/usr/bin/env bash
# Copies this profile to the LAN test host and brings it up.
#
# Deliberately additive and namespaced: it only ever touches ~/<remote-dir> on the remote host and
# the `kopia-e2e` compose project. It never restarts, reconfigures or inspects anything else running
# there, and never installs the CA into the host trust store.
#
# Usage: deploy.sh [ssh-host] [bind-address]
#   ssh-host      ssh alias/host of the test machine  (or set $KOPIA_E2E_SSH)
#   bind-address  address the test ports bind to on that machine
#                 (or set $KOPIA_E2E_BIND; defaults to 127.0.0.1 — pass the host's LAN IP to reach it from
#                  another machine. A wildcard bind is REFUSED: Docker's published ports bypass ufw,
#                  so 0.0.0.0 would silently expose these services to every network the host is on.)
#
# Credentials are generated once into .env.local (gitignored) rather than shipped as defaults, so a
# LAN-reachable deployment never runs on a password that is public in the repo.
set -euo pipefail

cd "$(dirname "$0")/.."

SSH_HOST="${1:-${KOPIA_E2E_SSH:?set KOPIA_E2E_SSH or pass the ssh host as the first argument}}"
BIND_ADDR="${2:-${KOPIA_E2E_BIND:-127.0.0.1}}"
REMOTE_DIR="${KOPIA_E2E_REMOTE_DIR:-kopia-e2e}"

# --- guardrails ---------------------------------------------------------------------------------
case "$BIND_ADDR" in
    0.0.0.0 | "::" | "[::]" | "*" | "")
        echo "error: refusing to bind to '$BIND_ADDR'." >&2
        echo "       Docker publishes past ufw, so a wildcard bind exposes SFTP/WebDAV/MinIO to" >&2
        echo "       every network this host is attached to. Pass 127.0.0.1 or a specific IP." >&2
        exit 1
        ;;
esac

# The remote dir is interpolated into `rm -rf ~/<dir>` in teardown.sh, so keep it a plain relative
# name — no slashes, no metacharacters, no traversal.
if ! [[ "$REMOTE_DIR" =~ ^kopia-e2e[A-Za-z0-9._-]*$ ]]; then
    echo "error: KOPIA_E2E_REMOTE_DIR must match ^kopia-e2e[A-Za-z0-9._-]*$ (got '$REMOTE_DIR')" >&2
    exit 1
fi

for f in certs/ca.crt certs/server.crt certs/server.key certs/minio/public.crt certs/minio/private.key; do
    [ -f "$f" ] || { echo "error: $f missing — run scripts/gen_certs.sh first" >&2; exit 1; }
done

# --- credentials --------------------------------------------------------------------------------
if [ ! -f .env.local ]; then
    echo "==> Generating credentials into .env.local (gitignored, keep it — the tests read it)"
    umask 077
    cat > .env.local <<EOF
SFTP_PASSWORD=$(openssl rand -hex 24)
WEBDAV_USERNAME=kopia
WEBDAV_PASSWORD=$(openssl rand -hex 24)
MINIO_ROOT_USER=kopiaadmin
MINIO_ROOT_PASSWORD=$(openssl rand -hex 24)
S3_BUCKET=kopia-e2e
EOF
fi
# shellcheck disable=SC1091
set -a; . ./.env.local; set +a

echo "==> Deploying to $SSH_HOST:~/$REMOTE_DIR (ports bind to $BIND_ADDR)"
ssh "$SSH_HOST" "mkdir -p ~/$REMOTE_DIR/certs/minio"

# Copy ONLY what the containers need. The CA *private key* never leaves this machine; the CA
# certificate does (mc needs it to verify MinIO), but that is public material.
# No --delete: never remove anything already on the host.
rsync -az docker-compose.yml nginx scripts "$SSH_HOST:~/$REMOTE_DIR/"
rsync -az certs/ca.crt certs/server.crt certs/server.key "$SSH_HOST:~/$REMOTE_DIR/certs/"
rsync -az certs/minio/public.crt certs/minio/private.key "$SSH_HOST:~/$REMOTE_DIR/certs/minio/"

# Bind address + credentials live only on the remote host and in .env.local — never in git.
ssh "$SSH_HOST" "umask 077; cat > ~/$REMOTE_DIR/.env" <<EOF
HOST_BIND=$BIND_ADDR
SFTP_PASSWORD=$SFTP_PASSWORD
WEBDAV_USERNAME=$WEBDAV_USERNAME
WEBDAV_PASSWORD=$WEBDAV_PASSWORD
MINIO_ROOT_USER=$MINIO_ROOT_USER
MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD
S3_BUCKET=${S3_BUCKET:-kopia-e2e}
KOPIA_E2E_SUBNET=${KOPIA_E2E_SUBNET:-172.28.0.0/24}
EOF

echo "==> Starting the kopia-e2e compose project"
# --force-recreate: rsyncing a rotated certificate does not change the compose config, so without
# this the containers keep serving the OLD cert while the pin is computed from the NEW file — which
# surfaces as a baffling pin mismatch. The SFTP host keys live in a volume and survive recreation.
ssh "$SSH_HOST" "cd ~/$REMOTE_DIR && docker compose up -d --force-recreate"

echo "==> Waiting for the test bucket"
bucket_ok=""
for _ in $(seq 1 20); do
    if ssh "$SSH_HOST" "cd ~/$REMOTE_DIR && docker compose logs createbucket 2>&1 | grep -q 'bucket ready'"; then
        bucket_ok=yes
        break
    fi
    sleep 3
done
if [ -z "$bucket_ok" ]; then
    echo "error: the test bucket was not created. Logs:" >&2
    ssh "$SSH_HOST" "cd ~/$REMOTE_DIR && docker compose logs createbucket 2>&1 | tail -20" >&2
    exit 1
fi
echo "    bucket ready"

echo "==> Running containers:"
ssh "$SSH_HOST" "cd ~/$REMOTE_DIR && docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'"

echo
echo "Next: scripts/trust_material.sh $SSH_HOST <test-host-address>   # host key + cert pins for the tests"
