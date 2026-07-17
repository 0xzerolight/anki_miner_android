# Media-processing foreground service declaration

The production manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and a non-exported `MiningForegroundService` with `android:foregroundServiceType="mediaProcessing"`. The service starts the typed foreground state for post-curation media extraction, shows ongoing progress and cancellation, holds a bounded wake lock, implements platform timeout callbacks, and stops foreground/service state on completion or failure.

Review the current [Android media-processing FGS requirements](https://developer.android.com/about/versions/15/changes/foreground-service-types) and Play declaration form at release time. Repository implementation is not proof of Play approval.

## Declaration narrative

Anki Miner performs user-initiated, potentially long-running local media conversion needed to create Anki audio clips and screenshots. After the user selects vocabulary, the app runs FFmpeg/FFprobe work which should continue while the app is not visible. The foreground notification reports progress and offers cancellation. The service does not perform unrelated synchronization, advertising, tracking, or continuous background work.

## Evidence video script

1. Start from an initialized AnkiDroid install and a release-equivalent Anki Miner build.
2. Select a local video and subtitles through SAF and start mining while the app is visible.
3. Show that pre-curation work does not consume the media-processing foreground phase.
4. Confirm vocabulary curation and show the foreground notification, progress, and cancel action.
5. Background or lock the device and show continued media processing.
6. Return to the app, complete or cancel, and show that the notification and service stop and that any committed Anki result is accurately reported.

## Required record

- [ ] Signed artifact/version and device/API used in the video are recorded.
- [ ] Video filename, SHA-256, duration, and private evidence-storage location are recorded; no private media or Anki content is exposed publicly.
- [ ] Manifest and runtime foreground-service identities were inspected from the signed artifact.
- [ ] Six-hour/24-hour platform budget and both timeout paths were exercised or otherwise evidenced.
- [ ] Notification permission denial, foreground-start failure, user cancel, process loss, and normal completion were reviewed.
- [ ] Play Console declaration text and final review status were captured.
