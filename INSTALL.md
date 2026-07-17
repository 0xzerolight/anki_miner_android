# Install Anki Miner for Android

No APK is approved for download yet. When one is published, it will appear as a GitHub prerelease in this repository. Do not install APKs copied from issues, chat messages, mirrors, workflow artifacts, or another repository.

## Requirements

- Android 8.0/API 26 or later on a 64-bit ARM (`arm64-v8a`) device
- AnkiDroid installed and fully initialized before Anki Miner requests database access
- the AnkiDroid versions listed as tested in the exact GitHub release notes
- internet access for the required UniDic download and any optional catalog resource downloads
- at least 1 GiB free during first setup

The APK is expected to be about 50 MiB. Required UniDic downloads about 45 MiB and occupies about 249 MiB after installation. The optional recommended dictionary downloads about 37 MiB and can require up to about 516 MiB while importing. Actual release sizes are recorded in its release notes.

Chromebooks, x86/x86_64 Android devices, Android versions below API 26, and devices without AnkiDroid are not supported by the GitHub APK.

## Verify the download

Download these files from the same immutable GitHub prerelease:

- `anki-miner-android-<version>-arm64-v8a.apk`
- `SHA256SUMS`
- `app-signing-certificate.pem`
- `app-signing-certificate.sha256`
- `release.json`

Confirm that the filename and version match the release notes. On a computer with `sha256sum`, place all release assets in one directory and run:

```sh
sha256sum --check --ignore-missing SHA256SUMS
```

Advanced users with the pinned Android Build Tools can also verify the APK signature and certificate:

```sh
apksigner verify --verbose --print-certs --Werr anki-miner-android-<version>-arm64-v8a.apk
```

The `sha256sum` command must report each downloaded asset as `OK`;
`--ignore-missing` permits the larger source/notices assets to remain
undownloaded. Separately, `apksigner` must report a valid signature. Its signer
certificate SHA-256 must match `app-signing-certificate.sha256` and the value in
`release.json`. Stop if any filename, hash, package identity, version, or
certificate differs.

An AAB is not installable and is not a GitHub prerelease asset. GitHub's automatically generated source ZIP/TAR files are source snapshots, not Android installers.

## Install

1. Install AnkiDroid from its official distribution channel.
2. Open AnkiDroid and complete its onboarding and storage setup. Create or open its collection once, then close or background it normally.
3. On the Android device, open the verified APK downloaded from the GitHub prerelease.
4. If Android asks, allow this browser or file manager to install unknown apps. This permission belongs to the installer app, not Anki Miner. Disable it again after installation if it is no longer needed.
5. Confirm that Android is installing `Anki Miner`, then open it.
6. Follow first-run setup. Grant AnkiDroid database access when requested, create the verified Anki Miner note type, and download/install UniDic.
7. The recommended Jitendex dictionary is optional. It provides offline definitions and needs substantial temporary storage while importing.

Anki Miner does not request broad access to the device's video library. Choose each video, subtitle, reading source, dictionary, or audio pack through Android's document picker.

## Update

The app does not check GitHub for updates. Read the repository's release notices or watch its GitHub Releases page.

To update, verify a newer APK exactly as above and install it over the existing app. Android accepts the update only when its version code is greater and it is signed by the same permanent certificate. Do not uninstall first: uninstalling removes Anki Miner settings, downloaded resources, caches, and recovery records.

Notes and media already committed to AnkiDroid remain in AnkiDroid after Anki Miner is removed. Manage those items in AnkiDroid.

Android normally rejects downgrades. If a prerelease is withdrawn, follow the release-specific rollback notice instead of forcing an older APK over a newer installation.

## Setup troubleshooting

- **AnkiDroid is not found:** install the official AnkiDroid app, open it, finish onboarding, then return to Anki Miner and check again.
- **AnkiDroid is uninitialized:** open AnkiDroid and confirm its collection is usable before returning.
- **Database permission is denied:** use Anki Miner's permission action. If Android no longer prompts, open Anki Miner's system app settings and review permissions.
- **UniDic cannot install:** confirm internet access and free space, reopen Anki Miner, and use verify/repair. Interrupted-download recovery is tested per release, but an invalid or changed partial download must be discarded.
- **Offline definitions are empty:** install the recommended dictionary or import a compatible Yomitan dictionary. Jisho is an optional network fallback and remains off until explicitly enabled in Settings.
- **Japanese sentence audio is unavailable:** install an offline Japanese Android text-to-speech voice. A missing voice must not prevent creation of an otherwise usable reading card.

For reproducible non-security problems, follow [SUPPORT.md](SUPPORT.md) and [TESTING.md](TESTING.md). Never attach private video, subtitle or reading content, Anki collection data, credentials, device serials, or unreviewed logs to a public issue.
