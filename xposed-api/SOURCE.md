# API 100 compile-only source

The files in `src/main/java/io/github/libxposed/api` are a pinned copy of the
libxposed API 100 interface used by Vector v2.0. The original `libxposed/api`
repository is no longer accessible at its historical URL. This copy was taken
from `SimonBaars/android-mac-changer`, commit
`eb193db4cff0d25352f39a33b46ee026338d44d9`, which identifies the upstream
API snapshot as `libxposed/api@88cc078`.

The files under `src/main/java/androidx/annotation` are minimal compile stubs
from the same pinned copy. These classes and the Xposed API are used only in
the Gradle `compileOnly` path and are not packaged in the APK. Upstream
libxposed API is published under Apache License 2.0; see `LICENSE-2.0.txt`.
