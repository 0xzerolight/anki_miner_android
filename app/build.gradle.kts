import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.android)
}

val pythonVersion = libs.versions.python.get()
val pythonTargetVersion = "3.13.9-0"
val androidNdkVersion = "28.2.13676358"
val s1aManifestProperty = providers.gradleProperty("ankiMinerS1aManifest")
val s1aRecipeKeyProperty = providers.gradleProperty("ankiMinerS1aRecipeKey")
val s1aBuildKeyProperty = providers.gradleProperty("ankiMinerS1aBuildKey")
val s1aPropertyPresence =
    listOf(
        s1aManifestProperty.isPresent,
        s1aRecipeKeyProperty.isPresent,
        s1aBuildKeyProperty.isPresent,
    )
require(s1aPropertyPresence.all { it == s1aPropertyPresence.first() }) {
    "S1a requires ankiMinerS1aManifest, ankiMinerS1aRecipeKey, and ankiMinerS1aBuildKey together"
}
val s1aEnabled = s1aPropertyPresence.first()

data class S1aWheels(val byAbi: Map<String, List<File>>)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun loadS1aWheels(): S1aWheels {
    val manifest = file(s1aManifestProperty.get()).canonicalFile
    require(manifest.isFile) { "S1a wheel manifest not found: $manifest" }
    require(manifest.name == "manifest.json") { "S1a wheel manifest filename must be manifest.json" }
    val document = JsonSlurper().parse(manifest) as Map<*, *>
    require(document["schema"] == 2) { "Unsupported S1a wheel manifest schema" }
    val recipeKey = document["recipe_key"] as? String ?: error("S1a recipe key missing")
    val buildKey = document["build_key"] as? String ?: error("S1a build key missing")
    require(recipeKey.matches(Regex("[0-9a-f]{64}"))) { "Invalid S1a recipe key" }
    require(buildKey.matches(Regex("[0-9a-f]{64}"))) { "Invalid S1a build key" }
    require(recipeKey == s1aRecipeKeyProperty.get()) { "S1a manifest recipe key is stale" }
    require(buildKey == s1aBuildKeyProperty.get()) { "S1a manifest build key is stale" }
    require(manifest.parentFile.name == "s1a-wheels-$buildKey") {
        "S1a manifest is not in its immutable build-key directory"
    }
    require(document["api_level"] == 26) { "S1a wheels must target Android API 26" }
    require(document["ndk"] == androidNdkVersion) { "S1a NDK version mismatch" }
    require(document["python_target"] == pythonTargetVersion) { "S1a Python target mismatch" }
    val wheels = document["wheels"] as? Map<*, *> ?: error("S1a manifest has no wheels")
    require(wheels.keys == setOf("arm64-v8a", "x86_64")) { "S1a manifest ABI set mismatch" }
    val allFilenames = mutableSetOf<String>()
    val byAbi = setOf("arm64-v8a", "x86_64").associateWith { abi ->
        val entries = wheels[abi] as? List<*> ?: error("S1a manifest has no $abi wheels")
        require(entries.size == 3) { "S1a $abi manifest must contain exactly three wheels" }
        val abiTag = abi.replace("-", "_")
        val expectedPrefixes =
            setOf(
                "chaquopy_libcxx-190000-0-py3-none-android_26_",
                "chaquopy_libmecab-0.996-0-py3-none-android_26_",
                "fugashi-1.5.2-0-cp313-cp313-android_26_",
            )
        val foundPrefixes = mutableSetOf<String>()
        val files = entries.map { raw ->
            val entry = raw as? Map<*, *> ?: error("Invalid S1a wheel entry")
            val filename = entry["filename"] as? String ?: error("S1a wheel filename missing")
            val expected = entry["sha256"] as? String ?: error("S1a wheel hash missing")
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid S1a wheel hash" }
            val suffix = "$abiTag.whl"
            require(filename.endsWith(suffix)) { "S1a wheel ABI mismatch: $filename" }
            foundPrefixes += filename.removeSuffix(suffix)
            require(allFilenames.add(filename)) { "Duplicate S1a wheel filename: $filename" }
            val elf = entry["elf"] as? Map<*, *> ?: error("S1a wheel ELF inventory missing")
            require(elf["abi"] == abi) { "S1a wheel ELF ABI mismatch: $filename" }
            val wheel = File(manifest.parentFile, filename).canonicalFile
            require(wheel.parentFile == manifest.parentFile && wheel.isFile) {
                "Invalid S1a wheel path: $filename"
            }
            require(sha256(wheel) == expected) { "S1a wheel hash mismatch: $filename" }
            wheel
        }
        require(foundPrefixes == expectedPrefixes) { "S1a $abi wheel identity set mismatch" }
        files
    }
    require(manifest.parentFile.listFiles { file -> file.extension == "whl" }
        ?.mapTo(mutableSetOf()) { it.name } == allFilenames) {
        "S1a publication directory contains an unmanifested or missing wheel"
    }
    return S1aWheels(byAbi)
}

val s1aWheels = if (s1aEnabled) loadS1aWheels() else null

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
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PYTHON_VERSION", "\"$pythonVersion\"")
        buildConfigField("boolean", "S1A_SPIKE_ENABLED", s1aEnabled.toString())
        buildConfigField(
            "String",
            "TOKENIZER_TEST_UNIDIC_ARCHIVE",
            "\"/data/local/tmp/anki-miner-tokenizer-unidic.zip\"",
        )
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

    sourceSets {
        getByName("androidTest").assets.srcDir(rootProject.file("golden"))
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val runtimeAbi =
            variant.productFlavors.single { (dimension, _) -> dimension == "runtimeAbi" }.second
        // Chaquopy resolves ABI filters from product flavors, not build types.
        variant.enable =
            (runtimeAbi == "emulator" && variant.buildType == "debug") ||
            (runtimeAbi == "device" && variant.buildType == "release") ||
            (s1aEnabled && runtimeAbi == "device" && variant.buildType == "debug")
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
    if (s1aWheels != null) {
        productFlavors {
            getByName("emulator") {
                pip {
                    s1aWheels.byAbi.getValue("x86_64").forEach { install(it.absolutePath) }
                    options("--no-index")
                }
            }
            getByName("device") {
                pip {
                    s1aWheels.byAbi.getValue("arm64-v8a").forEach { install(it.absolutePath) }
                    options("--no-index")
                }
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
