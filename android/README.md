# Android

The library for Android: an AAR with the sources of the JVM library, compiled against the Android SDK, and the native library of
monero-c for `arm64-v8a` (devices) and `x86_64` (the emulator).

The module is not part of the default build, because it needs the Android SDK and the native library: add it with `-Pandroid`.

## The native library

`libmonero_c.so` is not in this repository. monero-c builds it for Android and releases it as `monero-native-android-<version>.aar`, with
both ABIs, aligned for devices with 16 KB pages. Download that archive from the
[releases of monero-c](https://github.com/libmonero/monero-c/releases) (the version of the submodule, or a later one with the same ABI) and
point the build at it with `-Pmonero.c.android.native=<path>`, or with the environment variable `MONERO_C_ANDROID_NATIVE`. The value can
also be a folder with `arm64-v8a/libmonero_c.so` and `x86_64/libmonero_c.so`, e.g. for a library that you built.

## Build and publish

```
ANDROID_HOME=<sdk> ./gradlew -Pandroid -Pmonero.c.android.native=<path> :android:assembleRelease
ANDROID_HOME=<sdk> ./gradlew -Pandroid -Pmonero.c.android.native=<path> :android:publishToMavenLocal
```

Published as `org.libmonero:monero-kotlin-android` at the version of the library, with the dependencies of the POM (Jackson and the Android
artifact of JNA). An Android app consumes it with:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("org.libmonero:monero-kotlin-android:<version>")
}
```

The library loads on Android unchanged: `MoneroNative` asks JNA for `monero_c`, and JNA finds it in the `lib/<abi>` of the app, where the
AAR put it. An app that minifies gets the rules that the bridge needs from the AAR: JNA reads the interface, the callbacks and the
structures through reflection, and the models cross the native ABI as JSON through the names of their fields, which minification renames
when the rules do not keep them.

An app that bundles its own `libmonero_c.so` next to the AAR meets a duplicate file when the native libraries are merged, which its
packaging resolves:

```kotlin
android {
    packaging {
        jniLibs {
            pickFirsts += "**/libmonero_c.so"
        }
    }
}
```

## Requirements

- Android SDK with the platform 35 (compile) and its build tools; `ANDROID_HOME` set, or `sdk.dir` in `local.properties`.
- `minSdk` 28: the native library needs the bionic iconv of Android 9.
