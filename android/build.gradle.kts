// The library for Android: the sources of the JVM library, compiled against the Android SDK and packaged as an AAR with the native library of
// monero-c. The module is included only with -Pandroid (see settings.gradle.kts) because it needs the SDK and the native library.
//
// The native library is not in the repository. monero-c builds it for Android and releases it as monero-native-android-<version>.aar, with
// libmonero_c.so for arm64-v8a (devices) and x86_64 (the emulator). Point -Pmonero.c.android.native (or the environment variable
// MONERO_C_ANDROID_NATIVE) at that archive, or at a folder with <abi>/libmonero_c.so.
plugins {
    // no versions: the Kotlin plugin is on the classpath of the root build, and the Android one is added there with -Pandroid
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "org.libmonero.android"
    compileSdk = 35

    defaultConfig {
        // The API floor of libmonero_c.so for Android: the bionic iconv that boost.locale needs came with Android 9
        minSdk = 28

        // the rules that an app needs when it minifies
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // the sources of the library, the same ones the JVM build compiles
            kotlin.srcDir("../src/main/kotlin")
            // the native library, staged by the task below
            jniLibs.srcDir(layout.buildDirectory.dir("jniLibs"))
        }
    }

    publishing {
        // the release variant is what the publication below carries
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// where the native library comes from: a property or an environment variable
val moneroCNative: File? = providers.gradleProperty("monero.c.android.native")
    .orElse(providers.environmentVariable("MONERO_C_ANDROID_NATIVE"))
    .orNull
    ?.let { file(it) }

// the wiring of the staging below reads whether the native library is an archive or a folder at the time the
// build is configured, so an archive that appears between the configuration and the execution would stage
// nothing while the require below passes: the check runs where the wiring reads it
if (moneroCNative != null && !moneroCNative.exists()) {
    throw GradleException(
        "The native library of monero-c is set to ${moneroCNative.path} and is not there at the time of the configuration: " +
            "the staging wires itself by what the path is, so download or build it first"
    )
}

// fails with where to get the native library, since a build without it would give an AAR that loads and then dies on the phone
val requireMoneroC by tasks.registering {
    doLast {
        if (moneroCNative == null || !moneroCNative.exists()) {
            throw GradleException(
                "The native library of monero-c is not at ${moneroCNative ?: "any place"}: set -Pmonero.c.android.native (or the " +
                    "environment variable MONERO_C_ANDROID_NATIVE) to monero-native-android-<version>.aar from a release of monero-c, " +
                    "or to a folder with arm64-v8a/libmonero_c.so and x86_64/libmonero_c.so"
            )
        }
        val libraries = if (moneroCNative.isFile) {
            zipTree(moneroCNative).matching { include("jni/*/libmonero_c.so") }.files
        } else {
            fileTree(moneroCNative) { include("*/libmonero_c.so") }.files
        }
        if (libraries.isEmpty()) {
            throw GradleException("$moneroCNative has no <abi>/libmonero_c.so (or jni/<abi>/libmonero_c.so in an archive)")
        }
    }
}

// stages the native library in the layout of jniLibs, <abi>/libmonero_c.so, from the archive of monero-c or from a folder with that layout
val stageMoneroC by tasks.registering(Sync::class) {
    dependsOn(requireMoneroC)
    into(layout.buildDirectory.dir("jniLibs"))
    includeEmptyDirs = false
    if (moneroCNative != null && moneroCNative.isFile) {
        from(zipTree(moneroCNative)) {
            include("jni/*/libmonero_c.so")
            // jni/<abi>/libmonero_c.so is <abi>/libmonero_c.so
            eachFile { path = path.removePrefix("jni/") }
        }
    } else if (moneroCNative != null) {
        from(moneroCNative) {
            include("*/libmonero_c.so")
        }
    }

    // A library of another architecture has the same name, so it would be packaged and the app would die at its first load, and so would one
    // that is not the C ABI of monero-c. The ELF header has the magic number, the class (2 is 64 bits), the byte order (1 is little endian)
    // and, at offset 18, the machine; the exported names are in the strings of the file, which ISO_8859_1 reads one byte to one char
    doLast {
        val machines = mapOf("arm64-v8a" to 0xB7, "x86_64" to 0x3E)
        val abis = destinationDir.listFiles { file -> file.isDirectory }.orEmpty()
        // the path of the native library is read at configuration time to pick the source of the Sync, so a folder that an
        // archive replaces before the task runs stages nothing and this loop below would have nothing to check
        if (abis.isEmpty()) {
            throw GradleException("monero-c staged no ${machines.keys.joinToString()} library in ${destinationDir}")
        }
        for (abi in abis) {
            val machine = machines[abi.name]
                ?: throw GradleException("monero-c is built for ${machines.keys.joinToString()}, and ${abi.name} is none of them")
            val library = File(abi, "libmonero_c.so")
            val header = ByteArray(20)
            library.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < 20) {
                    throw GradleException("${abi.name}/libmonero_c.so is shorter than an ELF header")
                }
            }
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte() || header[4].toInt() != 2 || header[5].toInt() != 1
            ) {
                throw GradleException("${abi.name}/libmonero_c.so is not a 64-bit little endian ELF library")
            }
            val found = (header[19].toInt() and 0xFF shl 8) or (header[18].toInt() and 0xFF)
            if (found != machine) {
                throw GradleException(
                    "${abi.name}/libmonero_c.so is for the machine 0x${found.toString(16)}, and ${abi.name} is 0x${machine.toString(16)}"
                )
            }

            // the exported names are in the strings of the file, which ISO_8859_1 reads one byte to one char. The file
            // is scanned in chunks with an overlap, since an unstripped build is hundreds of megabytes
            var carriesTheAbi = false
            library.inputStream().use { input ->
                val chunk = ByteArray(1 shl 20)
                var carry = ByteArray(0)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    val window = ByteArray(carry.size + n)
                    carry.copyInto(window)
                    chunk.copyInto(window, carry.size, 0, n)
                    if (String(window, Charsets.ISO_8859_1).contains("monero_utils_get_abi_version")) {
                        carriesTheAbi = true
                        break
                    }
                    carry = window.copyOfRange(maxOf(0, window.size - 64), window.size)
                }
            }
            if (!carriesTheAbi) {
                throw GradleException("${abi.name}/libmonero_c.so does not export the C ABI of monero-c (monero_utils_get_abi_version)")
            }
        }
    }
}

// the staging feeds the merge of the native libraries of every variant
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(stageMoneroC) }

dependencies {
    // the same dependencies the JVM library declares, at the same versions
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // the Android artifact of JNA: it carries the native libraries of JNA for every ABI of Android, where the jar of the JVM library
    // carries the ones of the desktop
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}

publishing {
    publications {
        register<MavenPublication>("android") {
            groupId = rootProject.group.toString()
            artifactId = "monero-kotlin-android"
            version = rootProject.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}
