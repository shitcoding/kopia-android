#!/bin/sh
# Runs inside the atmoz/sftp container (as root) before sshd starts.
#
# The upload directory is a named volume. Docker creates the mount point owned by root:root, which
# replaces the ownership atmoz would otherwise give it, so the sftp user cannot write and every
# operation fails with SSH_FX_PERMISSION_DENIED. Hand the directory to the user that logs in.
set -e

user="${SFTP_USER:-kopia}"
dir="/home/$user/upload"

if [ -d "$dir" ]; then
    chown "$user" "$dir"
    chmod 755 "$dir"
fi
