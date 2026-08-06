# Notice overrides

Some dependencies **declare** a licence but **distribute no licence file**, so there is nothing for
the build to extract and reproduce. Their notices live here, one file per artifact, and are appended
to the generated reports so a released APK still carries them.

A file here is a verbatim copy of the project's own upstream licence text. It is **not** a template
filled in from the licence name: the copyright holder and years have to be the real ones, so each
file records where it was taken from. Reconstructing a notice from an SPDX template would put words
in someone else's mouth.

**Naming.** Java/Android artifacts: `<group>-<artifact-prefix>.txt`. npm packages: `npm-<name>.txt`,
with the scope's leading `@` dropped and `/` becoming `-` — so `@radix-ui/react-dialog` wants
`npm-radix-ui-react-dialog.txt`. The build prints the exact file name it looked for, so there is no
need to work it out by hand.

An artifact may need **more than one** notice. `com.github.luben-zstd-jni.txt` carries two, because
that artifact bundles a native library with its own separate copyright — check what an artifact
actually contains rather than assuming its declared licence covers all of it.

**Adding one.** The build tells you when you need to: `generate-js-licenses.mjs` exits non-zero and
names any npm package with no licence text, and the Gradle report lists artifacts with no embedded
file. Fetch the LICENSE from that project's own repository, drop it here with a provenance line, and
the reports pick it up.

These files exist only because the upstream artifact omits its own. If a future version starts
shipping one, delete the override — the extracted copy is authoritative.
