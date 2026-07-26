#!/usr/bin/env bash
# Generates the private CA and the server certificate used by the WebDAV/MinIO containers.
#
# This is the point of the whole profile: a certificate that NO public trust store accepts, so the
# client only connects if our custom-root-CA (S3 `rootCa`) or certificate-pinning (WebDAV
# `trustedServerCertificateFingerprint`) code actually works.
#
# The CA is a throwaway for this test host. It is NEVER installed into the host trust store —
# the app under test is given the PEM explicitly.
#
# Usage: gen_certs.sh <address-or-name> [more names ...]   (or set KOPIA_E2E_HOST)
#
# Pass every address the backends will be reached by. The S3 path verifies the hostname (only the
# trust anchor changes), so an address missing from the SAN fails closed.
set -euo pipefail

cd "$(dirname "$0")/.."
CERT_DIR="certs"
DAYS="${CERT_DAYS:-825}" # under the 825-day cap some TLS stacks enforce on server certs

# Every name/address the backends may be reached by. The S3 path keeps hostname verification ON
# (only the trust anchor changes), so the address used in the endpoint MUST appear here.
HOSTS=("$@")
if [ ${#HOSTS[@]} -eq 0 ]; then
    if [ -z "${KOPIA_E2E_HOST:-}" ]; then
        echo "usage: gen_certs.sh <address-or-name> [more names ...]" >&2
        echo "       (or set KOPIA_E2E_HOST). Pass every address the backends will be reached by —" >&2
        echo "       the S3 path verifies the hostname, so a missing SAN entry fails closed." >&2
        exit 1
    fi
    HOSTS=("$KOPIA_E2E_HOST")
fi

# Names the stack needs regardless of how the caller addresses it, appended if absent so forgetting
# one cannot produce a certificate that provisioning then rejects:
#   minio                 - the compose service name the provisioning `mc` container connects to,
#                           which verifies the certificate like any other client
#   localhost/127.0.0.1   - reaching the backends from the test host itself
for required in minio localhost 127.0.0.1; do
    case " ${HOSTS[*]} " in
        *" $required "*) ;;
        *) HOSTS+=("$required") ;;
    esac
done

# Regenerating silently would invalidate every pin already handed to a test run (and any cert
# already serving on the test host), so require an explicit --force / CERT_FORCE=1.
if [ -f "$CERT_DIR/ca.key" ] && [ "${CERT_FORCE:-}" != "1" ]; then
    echo "error: $CERT_DIR/ca.key already exists." >&2
    echo "       Regenerating mints a NEW CA and invalidates the pins in use." >&2
    echo "       Re-run with CERT_FORCE=1 to replace it, then re-run scripts/deploy.sh." >&2
    exit 1
fi

mkdir -p "$CERT_DIR/minio"

san=""
for h in "${HOSTS[@]}"; do
    if [[ "$h" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        san+="IP:$h,"
    else
        san+="DNS:$h,"
    fi
done
san="${san%,}"

echo "==> Generating private CA"
openssl req -x509 -newkey rsa:4096 -sha256 -days "$DAYS" -nodes \
    -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
    -subj "/CN=kopia-e2e homelab CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

echo "==> Generating server certificate for: ${HOSTS[*]}"
openssl req -newkey rsa:2048 -sha256 -nodes \
    -keyout "$CERT_DIR/server.key" -out "$CERT_DIR/server.csr" \
    -subj "/CN=${HOSTS[0]}"

openssl x509 -req -in "$CERT_DIR/server.csr" -sha256 -days "$DAYS" \
    -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
    -out "$CERT_DIR/server.crt" \
    -extfile <(printf 'subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$san")

rm -f "$CERT_DIR/server.csr"

# MinIO reads exactly these two filenames from its certs dir.
cp "$CERT_DIR/server.crt" "$CERT_DIR/minio/public.crt"
cp "$CERT_DIR/server.key" "$CERT_DIR/minio/private.key"

chmod 644 "$CERT_DIR/ca.crt" "$CERT_DIR/server.crt" "$CERT_DIR/minio/public.crt"
chmod 600 "$CERT_DIR/ca.key" "$CERT_DIR/server.key" "$CERT_DIR/minio/private.key"

echo
echo "CA certificate (give this to the app as S3 rootCa):  $PWD/$CERT_DIR/ca.crt"
echo "Server cert SHA-256 (WebDAV trustedServerCertificateFingerprint):"
openssl x509 -in "$CERT_DIR/server.crt" -noout -fingerprint -sha256 |
    sed 's/.*=//' | tr -d ':' | tr 'A-Z' 'a-z'
