# Media-processing foreground service

The production manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and a non-exported `MiningForegroundService` with `android:foregroundServiceType="mediaProcessing"`. The service starts typed foreground state only after final curation and only for work which handles media assets: video audio/screenshot extraction, Mokuro image materialization, offline TTS synthesis, or enabled local expression-audio assembly. Empty selections and text-only reading runs without an enabled media source do not start it. The service shows ongoing progress and cancellation, holds a bounded wake lock, implements platform timeout callbacks, and stops foreground/service state on completion or failure.

`POST_NOTIFICATIONS` is requested so progress and cancellation are visible in the notification drawer, but denial does not block mining or foreground-service startup. On Android 13 and later the platform still exposes the active foreground-service notice in Task Manager, while the app screen retains progress and cancellation controls.

Runtime behavior must be tested for every distributed APK. Play Console declaration and evidence acceptance are additional Play-only gates. Repository implementation and GitHub testing are not proof of Play approval.

## Behavior narrative

Anki Miner performs user-initiated, potentially long-running local media processing needed to create Anki audio clips and screenshots. Video mining uses FFmpeg/FFprobe to extract and convert selected clips and frames. Reading mining uses the same foreground type only when it materializes selected Mokuro images, synthesizes offline Japanese sentence audio, or assembles enabled local expression audio. The foreground notification reports progress and offers cancellation. The service does not perform plain text parsing, unrelated synchronization, advertising, tracking, or continuous background work.

## Every-channel device evidence

1. Start from an initialized AnkiDroid install and the exact signed candidate.
2. Select local video and subtitles through SAF and start mining while the app is visible.
3. Show that pre-curation work does not consume the media-processing foreground phase.
4. Confirm vocabulary curation and show the foreground notification, progress, and cancel action during FFmpeg media extraction.
5. Background or lock the device and show continued media processing, then return and show exact completion or cancellation cleanup.
6. Start a Mokuro reading run with its selected local image archive or enable an offline Japanese voice, curate a term, and show the reading media workload using the same notification and cancellation path.
7. Run a text-only reading source with TTS and local audio disabled and show that it completes without starting the media-processing foreground service.
8. Deny notification permission and confirm in-app controls and Android Task Manager visibility remain usable.
9. Exercise normal completion, user cancellation, process loss, foreground-start failure, platform timeout, and cleanup without a leaked service or media child.

## Required record for every channel

- [ ] Signed artifact/version, signing-certificate hash, device/API, and AnkiDroid version are recorded.
- [ ] Evidence filename, SHA-256, duration, and private evidence-storage location are recorded; no private media or Anki content is exposed publicly.
- [ ] Manifest and runtime foreground-service identities were inspected from the signed APK.
- [ ] Video, Mokuro image, offline-TTS/local-audio, no-media reading, and notification-denial branches were compared with this behavior narrative.
- [ ] Six-hour/24-hour platform budget and both timeout paths were exercised or otherwise evidenced.
- [ ] Foreground-start failure, user cancel, process loss, and normal completion were reviewed.

## Google Play only

- [ ] Current Android and Play foreground-service requirements were reviewed for the exact target SDK and artifact.
- [ ] Declaration text and justification accurately match the behavior evidence.
- [ ] A privacy-safe evidence video covering the required branches was submitted.
- [ ] Play Console declaration text, evidence identity, reviewer, and final acceptance status were captured.
