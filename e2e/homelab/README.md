# Home-server test profile for the remote storage backends

A private-network validation profile for the S3/WebDAV/SFTP backends (backlog **task-23.8 AC #2**).

It is a **manual, temporary** fixture for a LAN host you control — deliberately *not* an
internet-exposed or permanently-running service, and not part of CI.

## Why it exists

CI already runs the backends in Docker via Testcontainers, but on plain HTTP and with SFTP's
**insecure "trust any host key"** opt-in. That leaves the code paths users actually depend on for a
self-hosted server untested:

| Path | Covered only here |
|---|---|
| SFTP `knownHostsData` / `hostKeyFingerprint` | a **real, stable SSH host key** |
| WebDAV `trustedServerCertificateFingerprint` | **real TLS** with a self-signed certificate |
| S3 `rootCa` | **real TLS** with a private CA, hostname verification still on |

The negative controls are the point: they prove those verifications are *enforced*, not merely
present. A contract suite that talks to the genuine server would pass even if verification were
silently disabled.

## What it runs

Compose project `kopia-e2e`, everything namespaced `kopia-e2e*`:

| Service | Port (bound to the address you pass) | Purpose |
|---|---|---|
| `kopia-e2e-sftp` | 2222 | atmoz/sftp; host keys persisted in a volume so the fingerprint is stable |
| `kopia-e2e-webdav` | *(internal only)* | bytemark/webdav — the same server CI uses |
| `kopia-e2e-webdav-tls` | 8443 | nginx TLS terminator with the private-CA certificate |
| `kopia-e2e-minio` | 9000 / 9001 | MinIO serving HTTPS from the same certificate |
| `kopia-e2e-createbucket` | — | one-shot bucket provisioning, then exits |

Each service is capped at `cpus: 1.0` / `mem_limit: 512m` with bounded logs, and **no restart
policy** — a reboot leaves the host clean.

## Safety properties

Designed to be droppable onto a host that is already doing real work:

- **Never binds a wildcard.** `deploy.sh` refuses `0.0.0.0`/`::`, because Docker's published ports
  bypass ufw and a wildcard bind would silently expose these services to every attached network.
- **Additive only.** It writes `~/<remote-dir>` and the `kopia-e2e` compose project; `rsync` runs
  without `--delete`; nothing in `/etc`, systemd, ufw, or any existing container is touched.
- **The CA private key never leaves your machine.** Only the leaf pair and the CA *certificate* are
  copied to the host, and the CA is never installed into the host trust store.
- **Credentials are generated**, not shipped: `deploy.sh` writes random secrets to `.env.local`
  (gitignored) on first run. Nothing usable is committed.
- **Fully removable:** `teardown.sh` drops containers, network, volumes and the remote directory,
  and falls back to the project-name form if the directory is already gone.

## Usage

```bash
cd e2e/homelab

# 1. Private CA + server certificate. SANs must include every address you will use, because the
#    S3 path keeps hostname verification ON.
scripts/gen_certs.sh 203.0.113.10 backup-host backup-host.local minio localhost 127.0.0.1

# 2. Deploy. Second argument is the address the ports bind to on the remote host — use its LAN IP
#    to reach it from another machine, or 127.0.0.1 to keep it host-local.
scripts/deploy.sh backup-host 203.0.113.10

# 3. Collect the trust material (host key + certificate pin + credentials).
#    ⚠ The output contains secrets — eval it, don't commit or paste it.
eval "$(scripts/trust_material.sh backup-host 203.0.113.10)"

# 4. Run the backend tests against it.
cd ../.. && ./gradlew :storage:test --tests '*Homelab*'

# 5. Remove everything when done. Don't leave a LAN-reachable fixture up for days.
e2e/homelab/scripts/teardown.sh backup-host
```

Without the `KOPIA_HOMELAB_*` environment the tests **skip** (JUnit assumptions), so the normal
`:storage:test` run and CI are unaffected.

## What the tests assert

`storage/src/test/kotlin/org/kopiaKt/storage/homelab/HomelabBackendTest.kt`

- The shared `BlobStorageContractTest` against **all three** backends using only secure trust
  material — no insecure flags anywhere.
- **Negative controls** (the ones that prove enforcement):
  - a tampered SFTP host key is refused;
  - SFTP with *no* trust material fails closed with `HostKeyNotTrustedException`;
  - a wrong WebDAV certificate pin is refused;
  - S3 against the private-CA server **fails** when only the system trust store is used.
- A 20 MiB blob through the WebDAV TLS terminator, so the reverse-proxy `MOVE`/`Destination` path
  and body limits are exercised at realistic pack-blob size.

## Gotchas found while building this

- **A named volume mounted at the SFTP upload directory is created root-owned**, so the sftp user
  cannot write and every operation fails with permission-denied. `sftp-fix-upload-owner.sh` (run via
  atmoz's `/etc/sftp.d/` hook) hands it back to the user.
- **atmoz/sftp regenerates host keys** into the container layer on every recreate, which would break
  a pinned `known_hosts` on each deploy. `sftp-install-hostkeys.sh` generates them once into a volume.
- **The provisioning `mc` client verifies TLS too**, so the compose service name (`minio`) has to be
  in the certificate SAN or bucket creation fails closed.
- **`docker compose up -d` does not recreate on a changed certificate file** (the config is
  unchanged), so a rotated cert would leave the old one being served while the pin is computed from
  the new file. `deploy.sh` passes `--force-recreate`; the SFTP host keys survive in their volume.
- **mod_dav rejects a `MOVE` whose `Destination` scheme/authority disagrees** with the request it
  sees, so the nginx terminator rewrites `https://` → `http://` and forwards the original authority.
