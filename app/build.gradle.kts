import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.android)
}

val pythonVersion = libs.versions.python.get()

android {
    namespace = "com.ankiminer.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    flavorDimensions += "runtimeAbi"

    defaultConfig {
        applicationId = "com.ankiminer.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PYTHON_VERSION", "\"$pythonVersion\"")
    }

    productFlavors {
        create("device") {
            dimension = "runtimeAbi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("emulator") {
            dimension = "runtimeAbi"
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Future ffmpeg/ffprobe PIE executables must be extracted to real paths.
            useLegacyPackaging = true
        }
    }

    testOptions {
        animationsDisabled = true
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val runtimeAbi =
            variant.productFlavors.single { (dimension, _) -> dimension == "runtimeAbi" }.second
        // Chaquopy resolves ABI filters from product flavors, not build types.
        variant.enable =
            (runtimeAbi == "emulator" && variant.buildType == "debug") ||
            (runtimeAbi == "device" && variant.buildType == "release")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

chaquopy {
    defaultConfig {
        version = pythonVersion
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
