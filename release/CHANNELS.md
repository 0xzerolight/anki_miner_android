# Distribution channels

No channel is approved merely because an artifact can be built. Every distributed binary must pass the common gates for its exact source, inputs, signature, and artifact. Channel-specific requirements are additive.

## Common identity

GitHub and a later Google Play release use the same application ID, `com.ankiminer.android`, and one globally monotonic `versionCode` sequence. GitHub does not use a feature-divergent sideload flavor. The GitHub APK is the ARM64 `deviceRelease` application signed with the permanent app-signing key selected before the first public binary.

The private app-signing key is generated and used offline, never committed, uploaded as a workflow secret, placed on the self-hosted build runner, or passed in a command line. Keep encrypted backups in at least two separately controlled locations and test recovery before the first release. Publish only its X.509 certificate and SHA-256 fingerprint. Every candidate verifier must reject a certificate other than the recorded permanent certificate.

Version codes increase across all channels, not independently within each channel. A later Play version must have a greater version code than every GitHub APK which users may have installed.

## GitHub APK prerelease

The initial external-testing channel publishes one signed `arm64-v8a` APK as a GitHub prerelease. It supports Android API 26 and later on ARM64 devices. It is an alpha testing artifact, not a production or Play release, but legal, privacy, signing, source-delivery, physical-acceptance, and minimum real-device gates still apply.

Only these custom release assets are allowed, with `<version>` replaced by the exact manifest `versionName`:

- `anki-miner-android-<version>-arm64-v8a.apk`
- `SHA256SUMS`
- `app-signing-certificate.pem`
- `app-signing-certificate.sha256`
- `release.json`
- `anki-miner-android-<version>-corresponding-source.tar.zst`
- `anki-miner-android-<version>-notices.tar.zst`
- optionally, `anki-miner-android-<version>-redacted-evidence.tar.zst`

GitHub-generated source archives may also appear on the release page, but they are not the reviewed corresponding-source package. Do not attach an AAB, unsigned APK, debug or test APK, `.idsig`, raw device receipt, private log, user fixture, signing key, password, token, or machine-local path.

Create the release as a draft, attach and independently verify every asset, then publish it as a prerelease. Do not replace assets or move the tag after publication. Release notes identify the exact commit, APK SHA-256, signing-certificate SHA-256, supported API/ABI, tested AnkiDroid versions, open alpha test objectives, known limitations, privacy policy, installation guide, corresponding source, and support process.

Before the first public release, exercise the same scripts in the current private repository or a disposable private mirror using the explicit rehearsal channel. Use the protected publish workflow when the repository plan provides the required private-environment controls; otherwise use the local fail-closed publish script from a clean tagged checkout. A rehearsal record with its private-repository gate still `not_run` can never pass the normal final verifier. The resulting prerelease is a closed test available only to repository collaborators. After the exact downloaded APK and release path pass, bind the final report hash to the gate and regenerate the public-repository record.

The app has no automatic updater. A GitHub update is a higher-version APK signed by the same permanent certificate and installed over the existing app. Uninstalling first removes Anki Miner settings and private resources.

## Future Google Play release

Play is a later channel, not abandoned. Before its first release, transfer the same permanent app-signing key to Play App Signing through the then-current supported process and create a distinct upload key. Do not accept a different Play-generated app-signing identity if GitHub installations are expected to update to Play without uninstalling.

Play additionally requires a reviewed AAB, Play App Signing records, Data Safety submission, foreground-service declaration and evidence, store listing and App content declarations, pre-launch report, and internal then closed-track soak. Those requirements may remain open for a GitHub prerelease, but must never be marked passed or not applicable to Play.

## AnkiDroid exporter decision

Production uses only AnkiDroid's ContentProvider. It is required for collection reads as well as note and media writes, and the production path applies exact readback and durable recovery instead of blind retry.

The separately pinned AnkiconnectAndroid APK is a development compatibility canary only. It is not shipped, queried on localhost, offered as an install, or selected as a fallback. A material ContentProvider regression blocks a candidate and reopens the exporter decision; it does not silently activate the canary. `.apkg` export remains deferred.
