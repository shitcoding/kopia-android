# Third-Party Notices

KopiaKt includes code ported or adapted from the third-party projects listed
below. Their original license terms are reproduced verbatim.

The Kotlin rolling-hash splitter implementations are ports of the Go
`github.com/chmduquesne/rollinghash` packages (chosen so that content-defined
chunking matches Kopia's boundaries byte-for-byte).

---

## chmduquesne/rollinghash — MIT License

Ported into: `core/src/main/kotlin/org/kopiaKt/core/splitter/Buzhash32.kt`
Upstream: https://github.com/chmduquesne/rollinghash

```
Copyright 2015 Christophe-Marie Duquesne

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

---

## rollinghash/rabinkarp64 (adapted from restic/chunker) — BSD 2-Clause License

Ported into: `core/src/main/kotlin/org/kopiaKt/core/splitter/RabinKarp64.kt`
Upstream: https://github.com/chmduquesne/rollinghash (package `rabinkarp64`),
adapted from https://github.com/restic/chunker

```
Copyright (c) 2014, Alexander Neumann <alexander@bumpern.de>
Copyright (c) 2017, Christophe-Marie Duquesne <chmd@chmd.fr>

This file was adapted from restic https://github.com/restic/chunker

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## shadcn/ui — MIT License

`react-ui/src/components/ui/` contains 48 UI components vendored from
[shadcn/ui](https://github.com/shadcn-ui/ui). shadcn/ui is distributed as source to be copied
into a project rather than as a package, so these files appear in neither dependency report: they are
not on any classpath and not in the npm tree. Their notice therefore has to live here.

```
MIT License

Copyright (c) 2023 shadcn

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Runtime dependencies

KopiaKt depends on third-party libraries at runtime, all under permissive licences
(Apache-2.0, MIT, BSD): the Kotlin/AndroidX/Hilt stack, Bouncy Castle, zstd-jni,
lz4-java, OkHttp, the AWS SDK for Java v2, sshj, and the React interface stack
(React/Radix/TanStack/Recharts).

Their notices are **not** listed here, because a hand-written list drifts from the
dependency graph as soon as a dependency is added or upgraded. They are generated
instead, from the dependency sets that actually ship:

- `DEPENDENCY_LICENSES.md` — generated by the Gradle build from the release runtime
  classpath, which is exactly the set packaged into the APK.
- `JS_DEPENDENCY_LICENSES.md` — generated from the npm production dependency tree,
  covering the packages compiled into the bundled interface.

Both are produced during the Android build and shipped inside the app, where they
are readable under **Settings → About → Open-source licences** alongside this file.
They are not committed to the repository: reading them from a source checkout would
mean reading a copy that can silently go stale.

This matters because the app's packaging strips bundled `META-INF/LICENSE` and
`META-INF/NOTICE` metadata, so a released binary carries no dependency notices of its
own. Apache-2.0 §4(d) and BSD-2 clause 2 require those notices to accompany binary
distributions; the generated reports plus the in-app screen are how that obligation
is met.
