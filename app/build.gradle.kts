import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val pythonVersion = libs.versions.python.get()
val pythonTargetVersion = "3.12.12-0"
val androidNdkVersion = "28.2.13676358"

// Identity of the vendored Chaquopy wheels under app/wheels/ (built once via
// tools/wheels + tools/runtime-wheels; regenerate and update these keys on bump).
val runtimeWheelBuildKey =
    "bec101fa4d0ed89106d32e576440726ee6ff3159a7650a9396672ede8a54ddfa"
val s1aWheelBuildKey =
    "fcebd0499b2b9e8cacf622f7516676b2230d8507a24785578fe335dd04577325"

fun releaseValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(environmentName).orNull

fun quotedBuildValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val releaseVersionCodeRaw = releaseValue("ankiMinerVersionCode", "ANKI_MINER_VERSION_CODE")
val releaseVersionName = releaseValue("ankiMinerVersionName", "ANKI_MINER_VERSION_NAME")
val releaseSourceCommit = releaseValue("ankiMinerSourceCommit", "ANKI_MINER_SOURCE_COMMIT")
val releaseChannel = releaseValue("ankiMinerReleaseChannel", "ANKI_MINER_RELEASE_CHANNEL")
val releaseS1aAcceptedRaw =
    releaseValue("ankiMinerS1aArm64Accepted", "ANKI_MINER_S1A_ARM64_ACCEPTED")
val releaseS1aAccepted = releaseS1aAcceptedRaw?.toBooleanStrictOrNull() ?: false

val releaseStorePath = releaseValue("ankiMinerStoreFile", "ANKI_MINER_KEYSTORE")
val releaseStorePassword =
    releaseValue("ankiMinerStorePassword", "ANKI_MINER_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseValue("ankiMinerKeyAlias", "ANKI_MINER_KEY_ALIAS")
val releaseKeyPassword = releaseValue("ankiMinerKeyPassword", "ANKI_MINER_KEY_PASSWORD")

val chaquopyBuildPython =
    providers.environmentVariable("ANKI_MINER_CHAQUOPY_BUILD_PYTHON").orNull
        ?: throw GradleException(
            "ANKI_MINER_CHAQUOPY_BUILD_PYTHON is unset; source scripts/android-env.sh",
        )

// Vendored Chaquopy wheels under app/wheels/. --no-deps below means each per-ABI
// closure (common + runtime-abi + S1a tokenizer) must be complete.
fun wheelsIn(sub: String): List<File> {
    val dir = rootProject.file("app/wheels/$sub")
    val files = dir.listFiles { f -> f.extension == "whl" }?.sortedBy { it.name }.orEmpty()
    require(files.isNotEmpty()) { "No vendored wheels in app/wheels/$sub" }
    return files
}
val commonWheels = wheelsIn("common")
val deviceWheels = commonWheels + wheelsIn("arm64-v8a")
val emulatorWheels = commonWheels + wheelsIn("x86_64")

// Local release-signing config (never committed). Every release task validates
// the complete signing and identity contract before doing work.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }

android {
    namespace = "com.ankiminer.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = androidNdkVersion

    flavorDimensions += "runtimeAbi"

    defaultConfig {
        applicationId = "com.ankiminer.android"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCodeRaw?.toIntOrNull() ?: 1
        versionName = releaseVersionName ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PYTHON_VERSION", "\"$pythonVersion\"")
        buildConfigField("String", "PYTHON_TARGET_VERSION", "\"$pythonTargetVersion\"")
        buildConfigField("String", "RUNTIME_WHEEL_BUILD_KEY", "\"$runtimeWheelBuildKey\"")
        buildConfigField("boolean", "S1A_SPIKE_ENABLED", "true")
        buildConfigField("boolean", "S1A_PUBLICATION_VERIFIED", "true")
        buildConfigField("boolean", "S1A_ARM64_ACCEPTED", releaseS1aAccepted.toString())
        buildConfigField(
            "String",
            "SOURCE_COMMIT",
            quotedBuildValue(releaseSourceCommit ?: "development"),
        )
        buildConfigField(
            "String",
            "RELEASE_CHANNEL",
            quotedBuildValue(releaseChannel ?: "development"),
        )
        buildConfigField("String", "S1A_PUBLICATION_BUILD_KEY", "\"$s1aWheelBuildKey\"")
        buildConfigField(
            "String",
            "TOKENIZER_TEST_UNIDIC_ARCHIVE",
            "\"/data/local/tmp/anki-miner-tokenizer-unidic.zip\"",
        )
        buildConfigField(
            "String",
            "S1B_TEST_UNIDIC_ARCHIVE",
            "\"/data/local/tmp/anki-miner-s1b-unidic.zip\"",
        )
        manifestPlaceholders["ankiMinerSourceCommit"] = releaseSourceCommit ?: "development"
        manifestPlaceholders["ankiMinerReleaseChannel"] = releaseChannel ?: "development"
        manifestPlaceholders["ankiMinerS1aArm64Accepted"] = releaseS1aAccepted.toString()
        manifestPlaceholders["ankiMinerRuntimeWheelBuildKey"] = runtimeWheelBuildKey
        manifestPlaceholders["ankiMinerS1aPublicationBuildKey"] = s1aWheelBuildKey
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

    signingConfigs {
        create("release") {
            val storePath =
                releaseStorePath
                    ?: keystoreProps.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
            }
            storePassword = releaseStorePassword ?: keystoreProps.getProperty("storePassword")
            keyAlias = releaseKeyAlias ?: keystoreProps.getProperty("keyAlias")
            keyPassword = releaseKeyPassword ?: keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // ffmpeg/ffprobe PIE executables must be extracted to real paths.
            useLegacyPackaging = true
        }
    }

    testOptions {
        animationsDisabled = true
    }

    sourceSets {
        getByName("main").java.srcDir("src/main/ankidroidApi/kotlin")
        getByName("androidTest").assets.srcDir(rootProject.file("golden"))
        getByName("test").resources.srcDir(
            rootProject.file("tools/anki-contract/unicode/15.1.0"),
        )
        // Lets the JVM catalog-parity test diff the committed Python catalog JSON against
        // FrozenResourceCatalog without an emulator.
        getByName("test").resources.srcDir(
            rootProject.file("app/src/main/python/android_bridge"),
        )
    }
}

val validateReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Fail closed unless release signing and immutable identity are explicit."
    doLast {
        val failures = mutableListOf<String>()
        val versionCode = releaseVersionCodeRaw?.toIntOrNull()
        if (versionCode == null || versionCode <= 0) {
            failures += "ANKI_MINER_VERSION_CODE must be an explicit positive integer"
        }
        val validVersionName =
            releaseVersionName?.matches(
                Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"),
            ) == true
        if (!validVersionName) {
            failures += "ANKI_MINER_VERSION_NAME must be an explicit semantic version"
        }
        if (releaseSourceCommit?.matches(Regex("[0-9a-f]{40}")) != true) {
            failures += "ANKI_MINER_SOURCE_COMMIT must be an explicit lowercase 40-hex commit"
        }
        val allowedChannels = setOf("github-alpha", "production", "ci")
        if (releaseChannel !in allowedChannels) {
            failures += "ANKI_MINER_RELEASE_CHANNEL must be github-alpha, production, or ci"
        }
        if (releaseS1aAcceptedRaw !in setOf("true", "false")) {
            failures += "ANKI_MINER_S1A_ARM64_ACCEPTED must be explicitly true or false"
        }
        if (releaseChannel in setOf("github-alpha", "production") && releaseS1aAcceptedRaw != "true") {
            failures += "distribution channels require accepted ARM64 S1a physical evidence"
        }

        val configuredStorePath =
            releaseStorePath ?: keystoreProps.getProperty("storeFile")
        val configuredStore = configuredStorePath?.let(rootProject::file)
        if (configuredStore == null || !configuredStore.isFile) {
            failures += "release signing storeFile is missing or is not a regular file"
        }
        if ((releaseStorePassword ?: keystoreProps.getProperty("storePassword")).isNullOrBlank()) {
            failures += "release signing storePassword is missing"
        }
        if ((releaseKeyAlias ?: keystoreProps.getProperty("keyAlias")).isNullOrBlank()) {
            failures += "release signing keyAlias is missing"
        }
        if ((releaseKeyPassword ?: keystoreProps.getProperty("keyPassword")).isNullOrBlank()) {
            failures += "release signing keyPassword is missing"
        }
        if (failures.isNotEmpty()) {
            throw GradleException(failures.joinToString(prefix = "Release configuration rejected:\n- ", separator = "\n- "))
        }
    }
}

tasks.configureEach {
    if (name != "validateReleaseConfiguration" && name.contains("Release")) {
        dependsOn(validateReleaseConfiguration)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

chaquopy {
    defaultConfig {
        buildPython(chaquopyBuildPython)
        version = pythonVersion
    }
    productFlavors {
        getByName("emulator") {
            pip {
                emulatorWheels.forEach { install(it.absolutePath) }
                options("--no-index")
                options("--no-deps")
            }
        }
        getByName("device") {
            pip {
                deviceWheels.forEach { install(it.absolutePath) }
                options("--no-index")
                options("--no-deps")
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.jackson.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
