#!/usr/bin/env bash
# Removes the profile from the test host completely: containers, network, volumes and the remote
# directory. Touches nothing outside the `kopia-e2e` compose project.
#
# Usage: teardown.sh [ssh-host] [--keep-dir]
set -euo pipefail

KEEP_DIR="no"
args=()
for a in "$@"; do
    if [ "$a" = "--keep-dir" ]; then KEEP_DIR="yes"; else args+=("$a"); fi
done

SSH_HOST="${args[0]:-${KOPIA_E2E_SSH:?set KOPIA_E2E_SSH or pass the ssh host as the first argument}}"
REMOTE_DIR="${KOPIA_E2E_REMOTE_DIR:-kopia-e2e}"

# This value is interpolated into `rm -rf ~/<dir>` on a machine running things that matter, so keep
# it a plain relative name: no slashes, no traversal, no globs, no shell metacharacters.
if ! [[ "$REMOTE_DIR" =~ ^kopia-e2e[A-Za-z0-9._-]*$ ]]; then
    echo "error: KOPIA_E2E_REMOTE_DIR must match ^kopia-e2e[A-Za-z0-9._-]*$ (got '$REMOTE_DIR')" >&2
    exit 1
fi

echo "==> Removing the kopia-e2e compose project (containers + network + volumes) on $SSH_HOST"
# Use the project-NAME form as the fallback: if the remote directory was already removed, a
# compose-file-based teardown would silently do nothing and leave the containers behind.
# --remove-orphans is scoped to this compose project's label, so it cannot touch other projects
# running on the same host.
ssh "$SSH_HOST" "cd ~/$REMOTE_DIR 2>/dev/null && docker compose down -v --remove-orphans" ||
    ssh "$SSH_HOST" "docker compose -p kopia-e2e down -v --remove-orphans" ||
    echo "   (nothing to bring down)"

if [ "$KEEP_DIR" != "yes" ]; then
    echo "==> Removing ~/$REMOTE_DIR"
    ssh "$SSH_HOST" "rm -rf ~/$REMOTE_DIR"
fi

echo "==> Remaining kopia-e2e artifacts on the host (should be empty):"
ssh "$SSH_HOST" "docker ps -a --filter name=kopia-e2e --format '{{.Names}}'; \
                 docker volume ls --filter name=kopia-e2e --format '{{.Name}}'; \
                 docker network ls --filter name=kopia-e2e --format '{{.Name}}'"
