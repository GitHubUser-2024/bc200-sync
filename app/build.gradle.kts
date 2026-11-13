import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives outside the repo (build VM: ~/.android/bc200-release.properties).
val releaseProps = File(System.getProperty("user.home"), ".android/bc200-release.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "arpa.home.bc200sync"
    compileSdk = 35

    defaultConfig {
        applicationId = "arpa.home.bc200sync"
        minSdk = 29
        targetSdk = 35
        versionCode = 18
        versionName = "0.16"
    }

    signingConfigs {
        if (releaseProps != null) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
