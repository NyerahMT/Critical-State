plugins {
    id("com.android.application")
}

android {
    namespace = "com.nyerahworks.criticalstate"
    // Match the compile SDK used by the known-good F-Droid APK on the G-Mee.
    compileSdk = 35

    defaultConfig {
        // Fresh diagnostic package ID for the G-Mee install test. This package
        // has never been used by any prior Critical State APK, so Android cannot
        // reject it because of a stale package/signing-certificate collision.
        applicationId = "com.nyerahworks.criticalstate.gmee01"

        // Match the compatibility envelope of the known-good F-Droid APK.
        // minSdk 23 also keeps D8 on the older DEX 035 format instead of DEX 038.
        minSdk = 23
        targetSdk = 30
        versionCode = 7
        versionName = "0.1.6-gmee01-fresh-package"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // This diagnostic APK intentionally targets API 30 to match the known-good
    // F-Droid package that installs on the G-Mee. Suppress only the modern Play
    // Store target-SDK lint rule; all other release lint checks remain active.
    lint {
        disable.add("ExpiredTargetSdkVersion")
    }

    // The known-good F-Droid APK does not contain Gradle's encrypted dependency
    // metadata signing-block entry. Strip it for maximum compatibility with the
    // G-Mee's custom package installer.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }

        create("compatRelease") {
            val keystorePath = System.getenv("CS_KEYSTORE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CS_STORE_PASSWORD")
                keyAlias = System.getenv("CS_KEY_ALIAS")
                keyPassword = System.getenv("CS_KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("compatRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
