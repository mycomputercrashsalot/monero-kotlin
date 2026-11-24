// The example of the android module: an application that creates a full wallet in memory through the AAR of the module (the JNA layer of the
// library into libmonero_c.so), reads its version and its address, and connects it to a daemon to read the height of the chain.
//
// The daemon is a build field, so the example runs against a real node without mock data: the default is the regtest node of the
// docker-compose.yml of monero-c, which an emulator reaches from the host with `adb reverse tcp:18081 tcp:18081`. Point -Pdaemon.uri at another
// node, and pass -Pregtest=false when it is not a regtest one.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "org.libmonero.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.libmonero.example"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DAEMON_URI", "\"${providers.gradleProperty("daemon.uri").getOrElse("http://127.0.0.1:18081")}\"")
        buildConfigField("boolean", "REGTEST", providers.gradleProperty("regtest").getOrElse("true"))
    }

    buildFeatures {
        buildConfig = true
    }

    // the release of the example minifies the way an app of the store does, which is the only build that runs the
    // rules of the AAR: the interface, the callbacks and the structures of the bridge, and the fields of the models
    // that cross the abi, must all survive it
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":android"))
}
