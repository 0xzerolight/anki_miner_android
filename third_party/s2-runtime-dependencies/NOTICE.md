# S2 runtime dependency inventory

`manifest.json` records the complete locked `emulatorDebugRuntimeClasspath`
selected after adding the S2 Kotlin bridge dependencies. Its artifact SHA-256
values are the values enforced by Gradle dependency verification. The device
release runtime resolves the same component versions.

The three direct dependencies are AndroidX Core 1.17.0, Jackson Core 2.21.5,
and kotlinx-coroutines-core 1.11.0. All AndroidX, Kotlin, coroutines, JetBrains
Annotations, JSpecify, Guava ListenableFuture, and Jackson components in this
closure are Apache-2.0. License declarations were checked in their published
POM metadata; Guava ListenableFuture inherits its declaration from
`guava-parent:26.0-android`.

Jackson Core's packaged `META-INF/NOTICE` also records code bundled into the
JAR under MIT, BSL-1.0, and BSD-2-Clause terms. The corresponding packaged
texts are `META-INF/FastDoubleParser-LICENSE`,
`META-INF/FastDoubleParser-ThirdParty-LICENSE`, and
`META-INF/Schubfach-LICENSE`. These bundled terms are represented on the
Jackson component in `manifest.json`.

License and source references:

- AndroidX: https://source.android.com/docs/setup/about/licenses
- Jackson Core: https://github.com/FasterXML/jackson-core
- Kotlin and kotlinx.coroutines: https://github.com/Kotlin/kotlinx.coroutines
- Guava ListenableFuture: https://github.com/google/guava
- JetBrains Annotations: https://github.com/JetBrains/java-annotations
- JSpecify: https://github.com/jspecify/jspecify
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

`kotlinx-coroutines-android` is transitive through
`androidx.lifecycle:lifecycle-common`. It is deliberately not a direct
dependency. Lifecycle's coroutine scope implementation calls
`Dispatchers.Main.immediate`, so excluding the Android dispatcher would leave
that AndroidX API without its platform dispatcher even though S2's own code
does not dispatch to Main.
