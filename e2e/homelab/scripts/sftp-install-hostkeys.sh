#!/bin/sh
# Runs inside the atmoz/sftp container (as root) before sshd starts.
#
# atmoz/sftp generates throwaway host keys into the container layer, so every `docker compose up`
# would hand out a NEW host key — and a pinned known_hosts entry (the whole point of this profile)
# would break on each restart. Generate once into a named volume instead and install from there, so
# the fingerprint is stable for as long as the volume lives.
set -e

for type in ed25519 rsa; do
    key="/hostkeys/ssh_host_${type}_key"
    if [ ! -f "$key" ]; then
        ssh-keygen -q -t "$type" -f "$key" -N "" -C "kopia-e2e-sftp"
    fi
    cp "$key" "$key.pub" /etc/ssh/
    chmod 600 "/etc/ssh/ssh_host_${type}_key"
    chmod 644 "/etc/ssh/ssh_host_${type}_key.pub"
done
