// The example of the android module: an application that opens a light wallet through the AAR of the module (the JNA layer of the
// library into libmonero_c.so), against a monero-lws server, and reads its address, its height and its balance from it.
//
// The server and the wallet are build fields, so the example runs against a real server without mock data: the default is the
// monero-lws of the docker-compose.yml of the integration tests, which an emulator reaches from the host with
// `adb reverse tcp:8443 tcp:8443`. Point -Plws.uri at another server.
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
        buildConfigField("String", "LWS_URI", "\"${providers.gradleProperty("lws.uri").getOrElse("http://127.0.0.1:8443")}\"")
        buildConfigField("String", "WALLET_ADDRESS", "\"49MNnnrtLzj5vsWsHya28tKFPTcYfEnEYLfCqogA6Dw1TeqvHv5uRPTgjXSyD9pSoPVae3oZe3R5xHsGeC4VapjRV2m6gGj\"")
        buildConfigField("String", "WALLET_VIEW_KEY", "\"6cb8586d26fc0b5526035f1e39566d6aee6ff246dfc55bb49c6c99ea2643380f\"")
    }

    buildFeatures {
        buildConfig = true
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
