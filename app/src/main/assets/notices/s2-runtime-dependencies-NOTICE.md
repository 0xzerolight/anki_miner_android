# Android runtime dependency inventory

`manifest.json` records the complete locked `emulatorDebugRuntimeClasspath`
for the Kotlin bridge and Compose UI. Its artifact SHA-256 values are the values
enforced by Gradle dependency verification. Device release uses the same
production versions but omits the debug-only Compose tooling entries.

Direct dependencies are AndroidX Core 1.18.0, Activity Compose 1.13.0,
Lifecycle runtime/viewmodel Compose 2.10.0, DataStore Preferences 1.2.1,
Navigation Compose 2.9.8, the Compose 2026.06.00 BOM, Material 3, Compose
tooling preview, Media3 ExoPlayer and UI Compose 1.10.1, Jackson Core 2.21.5,
and kotlinx-coroutines-core 1.11.0. Debug additionally includes Compose UI
tooling and the UI test manifest. All AndroidX (including Media3 and its
ExifInterface transitive), Kotlin, coroutines, serialization, JetBrains
Annotations, JSpecify, Guava, and Jackson components in this closure are
Apache-2.0. Guava ListenableFuture inherits its declaration from
`guava-parent:26.0-android`; Media3 pulls full Guava 33.3.1-android into the
runtime closure under the same Apache-2.0 terms.

Jackson Core's packaged `META-INF/NOTICE` also records code bundled into the
JAR under MIT, BSL-1.0, and BSD-2-Clause terms. The corresponding packaged
texts are `META-INF/FastDoubleParser-LICENSE`,
`META-INF/FastDoubleParser-ThirdParty-LICENSE`, and
`META-INF/Schubfach-LICENSE`. These bundled terms are represented on the
Jackson component in `manifest.json`.

License and source references:

- AndroidX: https://source.android.com/docs/setup/about/licenses
- Jackson Core: https://github.com/FasterXML/jackson-core
- Kotlin: https://github.com/JetBrains/kotlin
- kotlinx.coroutines: https://github.com/Kotlin/kotlinx.coroutines
- kotlinx.serialization: https://github.com/Kotlin/kotlinx.serialization
- Guava ListenableFuture: https://github.com/google/guava
- JetBrains Annotations: https://github.com/JetBrains/java-annotations
- JSpecify: https://github.com/jspecify/jspecify
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

`kotlinx-coroutines-android` is transitive through AndroidX and deliberately
not a direct dependency. The Compose BOM is a version-alignment platform and
contains no runtime code.
