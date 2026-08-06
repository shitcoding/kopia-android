# Notice overrides

Some dependencies **declare** a licence but **distribute no licence file**, so there is nothing for
the build to extract and reproduce. Their notices live here, one file per artifact, and are appended
to the generated reports so a released APK still carries them.

A file here is a verbatim copy of the project's own upstream licence text. It is **not** a template
filled in from the licence name: the copyright holder and years have to be the real ones, so each
file records where it was taken from. Reconstructing a notice from an SPDX template would put words
in someone else's mouth.

**Naming.** Java/Android artifacts: `<group>-<artifact>.txt`, or `<group>.txt` where one notice
covers every artifact in the group (`org.bouncycastle.txt` serves bcprov, bcpkix and bcutil). npm
packages: `npm-<name>.txt`, with the scope's leading `@` dropped and `/` becoming `-` — so
`@radix-ui/react-dialog` wants `npm-radix-ui-react-dialog.txt`. The build prints the exact file
names it looked for, so there is no need to work it out by hand.

An artifact may need **more than one** notice. `com.github.luben-zstd-jni.txt` carries two, because
that artifact bundles a native library with its own separate copyright — check what an artifact
actually contains rather than assuming its declared licence covers all of it.

**Adding one.** The build tells you when you need to, and refuses to go on without it. Both halves
fail: `generate-js-licenses.mjs` exits non-zero on an npm package with no licence text, and
`copyLegalNotices` fails on a Java artifact that ships no licence file, naming the artifact, its
declared licence and the two file names it looked for. Fetch the LICENSE from that project's own
repository, drop it here with a provenance line, and the reports pick it up.

**When no override is needed.** An artifact under a licence that asks for no attribution needs
nothing here: Apache-2.0 — its text is identical for everyone and travels in the APK as this
repository's own root `LICENSE`, and §4(d) applies only where the artifact supplies a NOTICE, which
every such artifact on this classpath had extracted and inlined — and MIT-0. That list lives in
`app-android/build.gradle.kts` as `attributionFreeLicences`. Extending it is a legal judgement about
a licence, not a way to quieten a red build about an artifact.

`net.i2p.crypto:eddsa` is exempted by coordinate instead, in `noticeFreeArtifacts`: it is dedicated
to the public domain under CC0, but the report names its licence "Creative Commons Legal Code" —
the heading of every Creative Commons text, CC-BY included, which does require attribution. That
string must never become a blanket exemption.

These files exist only because the upstream artifact omits its own. If a future version starts
shipping one, delete the override — the extracted copy is authoritative, and the build warns that
the file matches nothing so it stops being included in the report.
