# Contributing to KopiaKt

Thanks for your interest! KopiaKt is a native Kotlin reimplementation of the
Kopia backup repository format for Android, aiming for byte-level compatibility
with repositories created by the Go implementation.

## Licensing of contributions (inbound = outbound)

By submitting a contribution (pull request or patch) you agree that it is
licensed under the project's license, the **Apache License, Version 2.0**. Please
sign off your commits to certify the [Developer Certificate of
Origin](https://developercertificate.org/):

```bash
git commit -s        # adds a "Signed-off-by" trailer
```

No separate CLA is required.

## Development

The Gradle project root **is the repository root**. A JDK 21 toolchain is
required (Gradle can provision one).

```bash
./gradlew :app-android:assembleDebug     # build the debug app
./gradlew :app-android:installDebug      # install to a device/emulator
./gradlew test                           # JVM/Robolectric unit tests
```

E2E (Maestro UI flows) need an Android emulator; see the scripts under
`e2e/maestro/scripts/` and the run wrapper `run_e2e.sh`.

### Lint gate (must pass)

CI and the project's Definition of Done require the lint gate to be green:

```bash
./gradlew detekt ktlintCheck             # the gate CI enforces
./gradlew ktlintFormat                   # auto-fix ktlint formatting
```

- ktlint code style is pinned to `intellij_idea` via the root `.editorconfig`.
- detekt and ktlint are **baselined**: a *new* violation must be fixed, not added
  to a baseline. Only regenerate a baseline for a deliberate, reviewed exception.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`,
  `fix:`, `refactor:`, `docs:`, `chore:`, `build:`, `perf:`, `style:`, `test:`.
- Keep pull requests focused and include tests for behavior changes.
- Any change to crypto, hashing, compression, or the pack/index format must
  preserve byte-level compatibility with Go Kopia.
