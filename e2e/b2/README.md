# Real-provider S3 tests (Backblaze B2)

Opt-in validation of the S3 backend against an actual hosted provider (backlog **task-23.8 AC #1**).

## Why

CI runs the S3 backend against MinIO in Docker on localhost. That covers our code against one
implementation, but not a real hosted provider: TLS to a public endpoint through the **system trust
store**, a real region/endpoint pair, a **scoped application key**, request signing against a
third-party implementation of the S3 protocol, and that provider's own error responses.

Backblaze B2 was chosen because it offers a free storage allowance that is not limited to a trial
period, which suits a bucket that exists only for occasional test runs. Check their current
[pricing](https://www.backblaze.com/cloud-storage/pricing) and
[transaction pricing](https://www.backblaze.com/cloud-storage/transaction-pricing) — the terms change.
The test data is tiny and deleted as it goes. Any S3-compatible provider works; only the environment
changes.

> This already paid for itself: the first run found that a wrong key produced a raw SDK error instead
> of `InvalidCredentialsException`, because neither `loadStorageConfig` (the **connect** path) nor
> `listBlobs` consulted the error taxonomy. MinIO never surfaced it since no test had exercised bad
> credentials on those paths.

## Bucket setup

Create a **private** bucket. Recommended settings:

| Setting | Value | Why |
|---|---|---|
| Files in bucket | **Private** | it is a repository, never public |
| Default encryption | **Disabled** | kopia already encrypts client-side; SSE adds nothing and is one more variable |
| Object Lock | **Disabled** | ⚠ immutability would break the tests (they delete blobs) and leave undeletable data |
| File lifecycle | **Keep only the last version** | otherwise deletes only *hide* files and old versions accrue forever |

Then create an **application key restricted to that bucket** (never the master key). Kopia needs only:

```
listBuckets, listFiles, readFiles, writeFiles, deleteFiles
```

Anything more is unnecessary; in particular `writeBuckets` can flip the bucket public and `shareFiles`
can mint public download URLs.

## Running

Credentials are read from the environment and never committed. `run_b2_tests.sh` pipes them straight
out of [`pass`](https://www.passwordstore.org/) into the test JVM — never echoed, never written to a
file, never passed as a command-line argument (which would be visible in the process table):

```bash
# secrets, in pass:
#   coding/kopia-kt/backblaze-app-key      -> the 31-char applicationKey on line 1
#   coding/kopia-kt/backblaze-app-key-id   -> the 25-char applicationKeyId
# (or put the id as a "keyID: <id>" line inside the first entry)

e2e/b2/run_b2_tests.sh
```

The bucket coordinates are **not** hardcoded (B2 bucket names are globally unique, so the repo
carries nobody's real bucket). Put yours in an untracked `e2e/b2/.env.local`:

```bash
cat > e2e/b2/.env.local <<'EOF'
KOPIA_B2_BUCKET=my-bucket
KOPIA_B2_ENDPOINT=s3.us-west-004.backblazeb2.com
KOPIA_B2_REGION=us-west-004
EOF
```

or pass them in the environment for a one-off run.

Not using `pass`? Export `KOPIA_B2_KEY_ID` and `KOPIA_B2_APP_KEY` yourself and run
`./gradlew :storage:test --tests '*B2Provider*'` directly.

Without `KOPIA_B2_BUCKET` the tests **skip** (JUnit assumptions), so CI and the normal
`:storage:test` gate are unaffected.

## What it asserts

`storage/src/test/kotlin/org/kopiaKt/storage/b2/B2ProviderContractTest.kt`

- The shared `BlobStorageContractTest` against the live provider — every blob operation, partial
  reads, listing, metadata, blob-id shapes.
- **Provider specifics** MinIO cannot vouch for:
  - a bad application key surfaces as `InvalidCredentialsException`, not an opaque SDK error
    (validates our error taxonomy against a third-party S3 implementation);
  - a blob round-trips, including a ranged read, over TLS validated by the **system trust store** —
    the default path, which the `e2e/homelab` profile deliberately bypasses with a private CA.

Each run uses a unique `contract-<uuid>/` prefix and deletes its objects as the tests finish (cleanup
runs on failure too), so concurrent runs cannot collide.

**If a run is interrupted** — killed JVM, crash, network loss mid-cleanup — its `contract-<uuid>/`
objects are orphaned, and because every run picks a fresh prefix nothing will ever reclaim them. They
are tiny, but check occasionally and delete any leftover `contract-*` prefixes from the provider
console (or `mc rm --recursive`). Setting the bucket's lifecycle to expire old versions bounds this
automatically.
