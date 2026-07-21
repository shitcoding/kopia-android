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

## Runtime dependencies

KopiaKt also depends on third-party libraries at build/runtime (all under
permissive licenses — Apache-2.0, MIT, BSD): the Kotlin/AndroidX/Compose/Hilt
stack (Apache-2.0), Bouncy Castle (MIT-style), zstd-jni (BSD-2), lz4-java /
OkHttp / Ktor / AWS SDK for Java v2 / sshj (Apache-2.0), and the React UI stack
(React/Radix/shadcn/Tailwind, MIT).

Note: the Android app's packaging currently strips bundled dependency
`META-INF/LICENSE`/`NOTICE` metadata. Apache-2.0 §4(d) and BSD-2 clause 2 require
those notices to accompany **binary** distributions, so before publishing APK/AAB
release builds a generated dependency-license report (e.g. via a Gradle
license-report plugin) or an in-app "open-source licenses" screen must be added.
This does not affect making the source repository public.
