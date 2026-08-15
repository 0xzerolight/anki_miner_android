# Security Policy

## Reporting a vulnerability

Please do not open a public issue for security vulnerabilities.

Report privately via GitHub Security Advisories:
<https://github.com/0xzerolight/anki_miner_android/security/advisories/new>

Anki Miner is maintained by a single person on a best-effort basis. You can expect an acknowledgment within a reasonable time.

Do not attach private media, mined text, or an unredacted diagnostics bundle to a report.

## Scope

In scope:

- Parsing of files you select: subtitles, EPUB, Aozora `.txt`, and Mokuro output.
- Media extraction through the bundled `ffmpeg` and `ffprobe` executables, including the Storage Access Framework file descriptors handed to those child processes.
- Card and collection writes through the AnkiDroid ContentProvider.
- Network handling for Jisho lookups, the GitHub release check, and the one-time resource downloads (UniDic, Yomitan dictionaries, frequency lists, pitch accent files).
- The on-device loopback `localaudio` source used for word audio.
- Redaction of the diagnostics bundle. Sensitive content surviving into an exported archive is a vulnerability.

Out of scope:

- Vulnerabilities in third-party software (AnkiDroid, Jisho, Yomitan dictionary data).
- Issues requiring access the user has already granted, such as a file the user picked or a directory the app owns.

## Supported versions

The latest APK on the [releases page](https://github.com/0xzerolight/anki_miner_android/releases/latest) is supported. Older versions may receive critical patches at maintainer discretion. The app does not update itself, so a fix reaches you only when you install the newer APK.
