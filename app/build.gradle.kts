import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val pythonVersion = libs.versions.python.get()
val pythonTargetVersion = "3.12.12-0"
val androidNdkVersion = "28.2.13676358"
val runtimeManifestProperty = providers.gradleProperty("ankiMinerRuntimeManifest")
val s1aManifestProperty = providers.gradleProperty("ankiMinerS1aManifest")
val s1aEnabled = s1aManifestProperty.isPresent
val chaquopyBuildPython =
    providers.environmentVariable("ANKI_MINER_CHAQUOPY_BUILD_PYTHON").orNull
        ?: throw GradleException(
            "ANKI_MINER_CHAQUOPY_BUILD_PYTHON is unset; source scripts/android-env.sh",
        )
val androidToolchainRoot =
    providers.environmentVariable("ANKI_MINER_ANDROID_TOOLCHAIN_ROOT").orNull
        ?: throw GradleException(
            "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT is unset; source scripts/android-env.sh",
        )
require(File(chaquopyBuildPython).isAbsolute) {
    "ANKI_MINER_CHAQUOPY_BUILD_PYTHON must be absolute"
}
val buildPythonVerificationOutput =
    providers.exec {
        commandLine(
            "python3.13",
            rootProject.file("scripts/verify_chaquopy_build_python.py").absolutePath,
            "verify",
            "--toolchain-root",
            androidToolchainRoot,
            "--python",
            chaquopyBuildPython,
        )
    }.standardOutput.asText.get().trim()
val buildPythonVerification =
    JsonSlurper().parseText(buildPythonVerificationOutput) as Map<*, *>
require(
    buildPythonVerification.keys ==
        setOf(
            "schema",
            "implementation",
            "version",
            "executable",
            "executable_sha256",
            "archive_sha256",
        ),
) {
    "Unexpected Chaquopy build Python verification result"
}
require(buildPythonVerification["schema"] == 1) {
    "Unsupported Chaquopy build Python verification schema"
}
require(buildPythonVerification["implementation"] == "CPython") {
    "Chaquopy build Python must be CPython"
}
require(buildPythonVerification["version"] == "3.12.13") {
    "Chaquopy build Python version mismatch"
}

data class RuntimeWheels(
    val buildKey: String,
    val common: List<File>,
    val byAbi: Map<String, List<File>>,
)

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

fun resolveManifest(configured: String): File {
    require(configured.isNotBlank()) { "Manifest path must not be blank" }
    val candidate = File(configured)
    return if (candidate.isAbsolute) {
        candidate.canonicalFile
    } else {
        rootProject.file(configured).canonicalFile
    }
}

fun loadRuntimeWheels(): RuntimeWheels {
    val manifest =
        runtimeManifestProperty.orNull?.let(::resolveManifest)
            ?: rootProject.file("tools/runtime-wheels/out/current/manifest.json").canonicalFile
    require(manifest.isFile) {
        "Android runtime wheel manifest not found: $manifest. " +
            "Run tools/runtime-wheels/build-runtime-wheels.sh first."
    }
    val verificationOutput =
        providers.exec {
            commandLine(
                "python3.13",
                rootProject.file("tools/runtime-wheels/runtime_wheels.py").absolutePath,
                "verify-publication",
                "--manifest",
                manifest.absolutePath,
            )
        }.standardOutput.asText.get().trim()
    val verification = JsonSlurper().parseText(verificationOutput) as Map<*, *>
    require(
        verification.keys ==
            setOf(
                "schema",
                "recipe_key",
                "build_key",
                "api_level",
                "ndk",
                "python_target",
                "groups",
            ),
    ) {
        "Unexpected runtime wheel publication verification result"
    }
    require(verification["schema"] == 1) {
        "Unsupported verified runtime wheel publication schema"
    }
    require(verification["api_level"] == 26) { "Runtime wheel API level mismatch" }
    require(verification["ndk"] == androidNdkVersion) { "Runtime wheel NDK mismatch" }
    require(verification["python_target"] == pythonTargetVersion) {
        "Runtime wheel Python target mismatch"
    }
    val recipeKey = verification["recipe_key"] as? String
        ?: error("Verified runtime wheel recipe key is missing")
    val buildKey = verification["build_key"] as? String
        ?: error("Verified runtime wheel build key is missing")
    require(recipeKey.matches(Regex("[0-9a-f]{64}"))) {
        "Verified runtime wheel recipe key is invalid"
    }
    require(buildKey.matches(Regex("[0-9a-f]{64}"))) {
        "Verified runtime wheel build key is invalid"
    }
    require(manifest.name == "manifest.json" && manifest.parentFile.name == "runtime-wheels-$buildKey") {
        "Runtime wheel manifest is not in its immutable build-key directory"
    }

    val rawGroups = verification["groups"] as? Map<*, *>
        ?: error("Verified runtime wheel groups are missing")
    require(rawGroups.keys == setOf("common", "arm64-v8a", "x86_64")) {
        "Verified runtime wheel group set is invalid"
    }
    val allFilenames = mutableSetOf<String>()
    fun filesFor(group: String, expectedCount: Int): List<File> {
        val filenames = rawGroups[group] as? List<*>
            ?: error("Verified runtime wheel group is missing: $group")
        require(filenames.size == expectedCount) {
            "Verified runtime wheel group $group has the wrong size"
        }
        return filenames.map { rawFilename ->
            val filename = rawFilename as? String
                ?: error("Verified runtime wheel filename is invalid")
            require(
                filename == File(filename).name &&
                    filename.endsWith(".whl") &&
                    allFilenames.add(filename),
            ) {
                "Verified runtime wheel filename is unsafe or duplicated: $filename"
            }
            if (group == "common") {
                require(filename.endsWith("-py3-none-any.whl")) {
                    "Runtime common wheel has an unexpected tag: $filename"
                }
            } else {
                require(filename.endsWith("android_26_${group.replace('-', '_')}.whl")) {
                    "Runtime wheel ABI differs from its verified group: $filename"
                }
            }
            File(manifest.parentFile, filename).canonicalFile.also { wheel ->
                require(wheel.parentFile == manifest.parentFile && wheel.isFile) {
                    "Verified runtime wheel is missing or escapes its publication: $filename"
                }
            }
        }
    }
    val common = filesFor("common", 6)
    val byAbi =
        setOf("arm64-v8a", "x86_64").associateWith { abi -> filesFor(abi, 7) }
    return RuntimeWheels(buildKey, common, byAbi)
}

fun loadS1aWheels(): S1aWheels {
    val manifest = resolveManifest(s1aManifestProperty.get())
    require(manifest.isFile) { "S1a wheel manifest not found: $manifest" }
    require(manifest.name == "manifest.json") { "S1a wheel manifest filename must be manifest.json" }
    // Publications are intentionally portable only across byte-identical active builder identities.
    // Recompute that identity here so direct Gradle invocation cannot bypass the publication gate.
    val verificationOutput =
        providers.exec {
            commandLine(
                "python3.13",
                rootProject.file("tools/wheels/s1a_wheels.py").absolutePath,
                "verify-publication",
                "--manifest",
                manifest.absolutePath,
            )
        }.standardOutput.asText.get().trim()
    val verification = JsonSlurper().parseText(verificationOutput) as Map<*, *>
    require(verification.keys == setOf("schema", "recipe_key", "build_key")) {
        "Unexpected S1a publication verification result"
    }
    require(verification["schema"] == 2) { "Unsupported verified S1a wheel manifest schema" }
    val document = JsonSlurper().parse(manifest) as Map<*, *>
    require(document["schema"] == 2) { "Unsupported S1a wheel manifest schema" }
    val recipeKey = document["recipe_key"] as? String ?: error("S1a recipe key missing")
    val buildKey = document["build_key"] as? String ?: error("S1a build key missing")
    require(recipeKey.matches(Regex("[0-9a-f]{64}"))) { "Invalid S1a recipe key" }
    require(buildKey.matches(Regex("[0-9a-f]{64}"))) { "Invalid S1a build key" }
    require(recipeKey == verification["recipe_key"]) { "S1a manifest recipe key is stale" }
    require(buildKey == verification["build_key"]) { "S1a manifest build key is stale" }
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
                "fugashi-1.5.2-0-cp312-cp312-android_26_",
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

val runtimeWheels = loadRuntimeWheels()
val s1aWheels = if (s1aEnabled) loadS1aWheels() else null
val s1bArm64TestsEnabled =
    providers.gradleProperty("ankiMinerS1bArm64Tests")
        .map { value ->
            require(value == "true") {
                "ankiMinerS1bArm64Tests must be exactly 'true' when supplied"
            }
            true
        }
        .orElse(false)
        .get()

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
        buildConfigField("String", "PYTHON_TARGET_VERSION", "\"$pythonTargetVersion\"")
        buildConfigField(
            "String",
            "RUNTIME_WHEEL_BUILD_KEY",
            "\"${runtimeWheels.buildKey}\"",
        )
        buildConfigField("boolean", "S1A_SPIKE_ENABLED", s1aEnabled.toString())
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
            // Future ffmpeg/ffprobe PIE executables must be extracted to real paths.
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
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val runtimeAbi =
            variant.productFlavors.single { (dimension, _) -> dimension == "runtimeAbi" }.second
        val s1bArm64Debug =
            s1bArm64TestsEnabled &&
                runtimeAbi == "device" &&
                variant.buildType == "debug"
        // Chaquopy resolves ABI filters from product flavors, not build types.
        variant.enable =
            (runtimeAbi == "emulator" && variant.buildType == "debug") ||
            (runtimeAbi == "device" && variant.buildType == "release") ||
            (s1aEnabled && runtimeAbi == "device" && variant.buildType == "debug") ||
            s1bArm64Debug
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
                val selected =
                    runtimeWheels.common +
                        runtimeWheels.byAbi.getValue("x86_64") +
                        (s1aWheels?.byAbi?.getValue("x86_64") ?: emptyList<File>())
                selected.forEach { install(it.absolutePath) }
                options("--no-index")
                options("--no-deps")
            }
        }
        getByName("device") {
            pip {
                val selected =
                    runtimeWheels.common +
                        runtimeWheels.byAbi.getValue("arm64-v8a") +
                        (s1aWheels?.byAbi?.getValue("arm64-v8a") ?: emptyList<File>())
                selected.forEach { install(it.absolutePath) }
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
