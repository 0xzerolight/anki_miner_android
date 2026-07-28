import com.android.build.api.variant.BuildConfigField
import org.gradle.api.tasks.Exec
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
    "01b8673597844082d525926e56c895c8e7e59f514334093789780295779eb76c"
val s1aWheelBuildKey =
    "fcebd0499b2b9e8cacf622f7516676b2230d8507a24785578fe335dd04577325"

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

val verifyVendoredWheelManifest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the exact provenance and SHA-256 of app/wheels."
    val manifest = rootProject.file("app/wheels/manifest.json")
    val verifier = rootProject.file("tools/wheels/vendored_wheel_manifest.py")
    inputs.file(manifest)
    inputs.file(verifier)
    inputs.file(rootProject.file("tools/runtime-wheels/sources.lock"))
    inputs.file(rootProject.file("tools/wheels/sources.lock"))
    inputs.files(rootProject.fileTree("app/wheels") { include("**/*.whl") })
    commandLine(
        chaquopyBuildPython,
        verifier.absolutePath,
        "check",
        "--wheels-root",
        rootProject.file("app/wheels").absolutePath,
        "--manifest",
        manifest.absolutePath,
    )
}

// Optional local release-signing config (never committed). See keystore.properties.example.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }

// Release builds inject the immutable build commit via -PankiMinerSourceCommit=<sha>
// (or the ANKI_MINER_SOURCE_COMMIT env var); dev builds keep "development".
val ankiMinerSourceCommit: String =
    (project.findProperty("ankiMinerSourceCommit") as String?)
        ?: System.getenv("ANKI_MINER_SOURCE_COMMIT")
        ?: "development"
val releaseBuildIntegrityScript =
    rootProject.file("tools/release/validate_release_build.py")
val validatedReleaseSourceCommit =
    providers.exec {
        // Run via `sh -c ... 2>&1` so the validator's stderr (the actionable
        // "requires a full lowercase Git SHA" / "SHA-256 mismatch" message) is
        // merged into captured stdout. providers.exec exposes only stdout, so
        // without this a fail-closed release would surface as a bare
        // "process finished with non-zero exit value 1" and hide the reason.
        commandLine(
            "sh",
            "-c",
            listOf(
                chaquopyBuildPython,
                releaseBuildIntegrityScript.absolutePath,
                "--build-type",
                "release",
                "--source-commit",
                ankiMinerSourceCommit,
                "--wheels-root",
                rootProject.file("app/wheels").absolutePath,
                "--manifest",
                rootProject.file("app/wheels/manifest.json").absolutePath,
            ).joinToString(" ") { "'" + it + "'" } + " 2>&1",
        )
        isIgnoreExitValue = true
    }.standardOutput.asText.map { raw ->
        val trimmed = raw.trim()
        if (!Regex("^[0-9a-f]{40}$").matches(trimmed)) {
            throw GradleException(
                trimmed.ifBlank { "release build integrity validation failed" },
            )
        }
        trimmed
    }
val validateReleaseSourceCommit by tasks.registering {
    group = "verification"
    description = "Fail release builds without an immutable source commit."
    doLast {
        if (!Regex("^[0-9a-f]{40}$").matches(ankiMinerSourceCommit)) {
            throw GradleException(
                "Release builds require a full lowercase Git SHA via " +
                    "-PankiMinerSourceCommit=<sha> or ANKI_MINER_SOURCE_COMMIT.",
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("ReleaseBuild")) {
        dependsOn(validateReleaseSourceCommit)
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
        versionCode = 8
        versionName = "0.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PYTHON_VERSION", "\"$pythonVersion\"")
        buildConfigField("String", "PYTHON_TARGET_VERSION", "\"$pythonTargetVersion\"")
        buildConfigField("String", "RUNTIME_WHEEL_BUILD_KEY", "\"$runtimeWheelBuildKey\"")
        buildConfigField("boolean", "S1A_SPIKE_ENABLED", "true")
        buildConfigField("boolean", "S1A_PUBLICATION_VERIFIED", "true")
        buildConfigField("boolean", "S1A_ARM64_ACCEPTED", "false")
        buildConfigField("String", "SOURCE_COMMIT", "\"$ankiMinerSourceCommit\"")
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
        manifestPlaceholders["ankiMinerSourceCommit"] = ankiMinerSourceCommit
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
                keystoreProps.getProperty("storeFile")
                    ?: System.getenv("ANKI_MINER_KEYSTORE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword =
                    keystoreProps.getProperty("storePassword")
                        ?: System.getenv("ANKI_MINER_KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: "anki-miner"
                keyPassword =
                    keystoreProps.getProperty("keyPassword")
                        ?: System.getenv("ANKI_MINER_KEY_PASSWORD")
            }
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
            // Sign only when a local keystore is configured (present for release builds).
            if (keystorePropsFile.exists() || System.getenv("ANKI_MINER_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // Artifact prerequisites consume this provider directly. `-x` can skip the named
        // lifecycle checks above, but cannot remove validation from release manifest and
        // BuildConfig evaluation without also excluding inputs required to package the app.
        variant.buildConfigFields!!.put(
            "SOURCE_COMMIT",
            validatedReleaseSourceCommit.map { commit ->
                BuildConfigField("String", "\"$commit\"", "Validated release source commit")
            },
        )
        variant.manifestPlaceholders.put(
            "ankiMinerSourceCommit",
            validatedReleaseSourceCommit,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Upstream warning from Gradle plugin `com.chaquo.python` 17.0.0, not this build script:
// `product/gradle-plugin/src/main/kotlin/com/chaquo/python/PythonTasks.kt` createSrcTask
// accesses Task.project inside merge*PythonSources doLast (lines 148, 166, 173). The plugin
// binary owns those actions, so fixing the warning locally requires upgrading or forking
// Chaquopy. Keep it visible until upstream fixes it; do not suppress Gradle warnings globally.
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

tasks.named("preBuild") {
    dependsOn(verifyVendoredWheelManifest)
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
