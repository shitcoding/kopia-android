# Security Policy

KopiaKt connects to backup repositories, reads and restores encrypted backup
data, and implements cryptographic primitives (AES-256-GCM, HKDF, BLAKE2/BLAKE3,
HMAC-SHA256) for byte-level compatibility with the Kopia repository format.
Security reports are taken seriously.

## Reporting a vulnerability

Please report security vulnerabilities **privately** — do **not** open a public
issue.

- Preferred: GitHub's **private vulnerability reporting** — the repository
  **Security** tab → **Report a vulnerability** (GitHub Security Advisories).
  (This must be enabled in the repository settings; if the "Report a
  vulnerability" button is not present, the maintainer has not enabled it yet.)
- Fallback: if you cannot use GitHub, contact the maintainer via the email listed
  on their GitHub profile.

Please include a description, the affected version or commit, and reproduction
steps if you have them. We aim to acknowledge reports promptly and will
coordinate disclosure once a fix is available.

## Scope

**In scope:** the Kotlin repository-format, encryption, storage, and Android
code (`core`, `snapshot`, `storage`, `android`, `app-android`) and the WebView
JavaScript bridge.

**Out of scope:** vulnerabilities in upstream dependencies (please report those
to the respective projects), and the development-only Go helper tooling under
`testvectors/` and `sync-tools/` (not shipped in the app).

## Supported versions

KopiaKt is pre-1.0 software; only the latest `main` is supported. There are no
long-term-support branches yet.
