plugins {
    id("com.android.application")
}

android {
    namespace = "com.nyerahworks.criticalstate"
    // Match the compile SDK used by the known-good F-Droid APK on the G-Mee.
    compileSdk = 35

    defaultConfig {
        // Fresh diagnostic package so this test cannot collide with any previous APK.
        applicationId = "com.nyerahworks.criticalstate.gmee02"

        // Match the compatibility envelope of the known-good F-Droid APK.
        minSdk = 23
        targetSdk = 30
        versionCode = 8
        versionName = "0.1.7-gmee02-resource-table"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        disable.add("ExpiredTargetSdkVersion")
    }

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
