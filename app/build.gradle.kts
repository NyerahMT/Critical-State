plugins {
    id("com.android.application")
}

android {
    namespace = "com.nyerahworks.criticalstate"
    compileSdk = 36

    defaultConfig {
        // Separate diagnostic package so it can never conflict with a prior install.
        applicationId = "com.nyerahworks.criticalstate.compat"
        minSdk = 26
        targetSdk = 33
        versionCode = 4
        versionName = "0.1.3-compat-release-signing"

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
