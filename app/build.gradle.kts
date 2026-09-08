plugins {
    id("com.android.application")
}

android {
    namespace = "com.nyerahworks.criticalstate"
    // Match the compile SDK used by the known-good F-Droid APK on the G-Mee.
    compileSdk = 35

    defaultConfig {
        // Separate diagnostic package so it can never conflict with a prior install.
        applicationId = "com.nyerahworks.criticalstate.compat"

        // Match the compatibility envelope of the known-good F-Droid APK.
        // minSdk 23 also keeps D8 on the older DEX 035 format instead of DEX 038.
        minSdk = 23
        targetSdk = 30
        versionCode = 5
        versionName = "0.1.4-gmee-dex035"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
