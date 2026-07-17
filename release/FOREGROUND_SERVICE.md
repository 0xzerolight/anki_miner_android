# Media-processing foreground service declaration

The production manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and a non-exported `MiningForegroundService` with `android:foregroundServiceType="mediaProcessing"`. The service starts the typed foreground state only after final curation and only for work which handles media assets: video audio/screenshot extraction, Mokuro image materialization, offline TTS synthesis, or enabled local expression-audio assembly. Empty selections and text-only reading runs without an enabled media source do not start it. The service shows ongoing progress and cancellation, holds a bounded wake lock, implements platform timeout callbacks, and stops foreground/service state on completion or failure.

`POST_NOTIFICATIONS` is requested so progress and cancellation are visible in the notification drawer, but denial does not block mining or foreground-service startup. On Android 13 and later the platform still exposes the active foreground-service notice in Task Manager, while the app screen retains progress and cancellation controls.

Review the current [Android media-processing FGS requirements](https://developer.android.com/about/versions/15/changes/foreground-service-types) and Play declaration form at release time. Repository implementation is not proof of Play approval.

## Declaration narrative

Anki Miner performs user-initiated, potentially long-running local media processing needed to create Anki audio clips and screenshots. Video mining uses FFmpeg/FFprobe to extract and convert selected clips and frames. Reading mining uses the same foreground type only when it materializes selected Mokuro images, synthesizes offline Japanese sentence audio, or assembles enabled local expression audio. The foreground notification reports progress and offers cancellation. The service does not perform plain text parsing, unrelated synchronization, advertising, tracking, or continuous background work.

## Evidence video script

1. Start from an initialized AnkiDroid install and a release-equivalent Anki Miner build.
2. Select a local video and subtitles through SAF and start mining while the app is visible.
3. Show that pre-curation work does not consume the media-processing foreground phase.
4. Confirm vocabulary curation and show the foreground notification, progress, and cancel action during FFmpeg media extraction.
5. Background or lock the device and show continued media processing, then return and show exact completion or cancellation cleanup.
6. Start a Mokuro reading run with its selected local image archive or enable an offline Japanese voice, curate a term, and show the reading media workload using the same notification and cancellation path.
7. Run a text-only reading source with TTS and local audio disabled and show that it completes without starting the media-processing foreground service.
8. Show that every foreground notification stops and that any committed Anki result is accurately reported.

## Required record

- [ ] Signed artifact/version and device/API used in the video are recorded.
- [ ] Video filename, SHA-256, duration, and private evidence-storage location are recorded; no private media or Anki content is exposed publicly.
- [ ] Manifest and runtime foreground-service identities were inspected from the signed artifact.
- [ ] Video, Mokuro image, offline-TTS/local-audio, and no-media reading branches were compared with the declaration and evidence video.
- [ ] Six-hour/24-hour platform budget and both timeout paths were exercised or otherwise evidenced.
- [ ] Notification permission denial, foreground-start failure, user cancel, process loss, and normal completion were reviewed.
- [ ] Play Console declaration text and final review status were captured.
