# External and manual gates

Automation cannot establish the following results. Record the exact channel, source commit, signed artifact, resource publications, device model and public build identity, AnkiDroid version, operator, UTC time, outcome, and evidence hash for each run. Do not record a personal device serial, private path, private media, subtitle text, Anki content, or credential in public evidence.

## Required before a GitHub APK prerelease

- [ ] Selected S1a tokenizer publication passes the full parity corpus on a supported physical ARM64 device and produces the exact source-bound acceptance receipt required by release packaging.
- [ ] Three clean runs on a representative 3–5 GiB ARM64 device meet the defined cold-initialization target and peak-memory ceiling.
- [ ] Representative novel/reading tokenization throughput and storage use are recorded.
- [ ] The exact signed APK installs from a clean state and over the preceding public version; its package, version, and permanent signing certificate match the release record.
- [ ] AnkiDroid is installed and initialized before permission is requested. Absent, uninitialized, permission-denied, incompatible, force-stopped, and upgraded cases are understandable and recoverable.
- [ ] Real local video/subtitle mining creates a correctly rendered AnkiDroid card with verified audio and screenshot.
- [ ] TXT/Aozora, reading-subtitle, EPUB, and Mokuro-plus-archive runs each create a correctly rendered AnkiDroid card; EPUB covers and Mokuro page images are verified where present.
- [ ] Reading with an installed offline Japanese voice creates playable sentence audio; a missing or failing offline voice leaves the card usable and reports the retained warning.
- [ ] Imported dictionary, frequency, pitch-accent, known-word, bundled-wordset, and local expression-audio sources affect cards in the configured order without remote media fetches.
- [ ] Cancel during probing, extraction, media insertion, and note insertion produces an accurate result without blind retry or leaked work.
- [ ] Screen-off/background processing, platform foreground-service timeout, process kill, low storage, non-seekable provider fallback, and recovery are exercised.
- [ ] Empty selections and text-only reading without media options finish without a foreground service; media work remains usable after notification permission denial and is visible in Android's Task Manager.

## Resource download interruption and resume

- [ ] Start a required UniDic download, wait until measurable progress is stored, then force-stop Anki Miner from Android system settings or an explicitly recorded ADB command.
- [ ] Relaunch the exact signed APK and resume or retry the same catalog install. Confirm that the partial download is reused only when its immutable URL, expected size, validators, and local state remain valid.
- [ ] Complete installation and verify the final size/hash/tree. Confirm that no corrupt install, duplicate installed resource, unbounded temporary file, or stale active-operation state remains.
- [ ] Repeat the interruption on the recommended dictionary download/import boundary. Confirm that a partial archive resumes safely, while an interrupted transactional import either completes from a verified archive or restores the previous dictionary slot.
- [ ] Repeat once with the partial file changed or catalog identity made stale in a controlled test. Confirm that unsafe partial state is discarded instead of trusted.

## User experience and compatibility

- [ ] First-run setup, resource download/import/repair, offline and corrupt-resource errors, and custom Yomitan import are understandable and recoverable without developer instructions.
- [ ] Jisho remains off by default and its disclosure appears before opt-in; offline-only behavior is confirmed after disabling it.
- [ ] Rotation, process recreation, navigation, dark theme, large text, screen-reader labels, empty states, and destructive/retry actions are reviewed.
- [ ] API 26 and current API 36 behavior, 4 KiB and 16 KiB page-size devices, and intended ARM64 ABI coverage are recorded.
- [ ] Installation from the documented GitHub flow, manual same-signature update, rejected downgrade, and uninstall data consequences match [INSTALL.md](../INSTALL.md).
- [ ] Every known limitation and any incompletely explored alpha objective appears in the GitHub release notes and [TESTING.md](../TESTING.md).

## GitHub release system

- [ ] Privacy policy and monitored privacy/support contacts are publicly reachable without login.
- [ ] Package name and permanent signing certificate are registered or cleared for direct distribution under current Android developer-verification requirements.
- [ ] The Git tag and all draft assets were independently verified before publication.
- [ ] Release immutability, least-privilege publishing access, signing-key separation, support intake, halt criteria, rollback owner, and source retention are configured and recorded.
- [ ] The final release remains marked as a prerelease and contains only the allowed assets.

## Private-repository release rehearsal

Complete this gate in the current private repository or a disposable private
mirror before preparing final assets for the public repository. Use the exact
protected-main commit and annotated tag, but do not copy the signing key,
passwords, raw device receipts, or self-hosted runner credentials. Fill every
other approval, bind the document to the exact signed APK, and leave only this
gate as a valid `not_run` entry. Use the release scripts' explicit
`--private-rehearsal` mode. Dispatch the publish workflow with
`private_rehearsal: true` when protected private environments are available;
otherwise run `scripts/publish-github-prerelease.sh --private-rehearsal TAG`
from the clean tagged checkout. That channel is accepted only in a private
repository and is rejected by the final public verifier. Only repository
collaborators can download a private-repository prerelease.

- [ ] Create and publish a private rehearsal from the exact signed APK, public certificate,
  corresponding-source archive, notices, record, checksums, and release notes.
- [ ] Download the draft into a clean directory through the same authenticated
  GitHub path testers or maintainers will use. Re-run `verify-assets`, verify the
  single signer and permanent certificate, and compare the APK SHA-256 with the
  approval document.
- [ ] Install that exact downloaded APK on a clean supported ARM64 device and
  complete first-run setup, AnkiDroid provider permission, one reading card,
  one local video card, media playback, app restart, and same-signature update.
- [ ] Confirm that the protected publish workflow in the private mirror
  that it rejects changed assets, an unprotected/non-main tag, a second signer,
  a noncanonical certificate, a stale approval, and an unexpected asset before
  successfully publishing only the valid draft as a prerelease.
- [ ] Record a redacted report containing the source commit/tree, tag, exact
  signed APK SHA-256, certificate SHA-256, private repository handle, workflow
  run URL or stable ID, device class, UTC time, outcomes, and cleanup decision.
  Hash the final report, change `private_repository_rehearsal` to `passed`, and
  use that hash when regenerating the final public-repository assets without the
  rehearsal flag.

Do not reuse this result after the source, signed APK, certificate, release
tooling, or publication path changes. Keep the repository and release private
to the closed test group. If cleanup is required after enabling release
immutability, delete the entire rehearsal release; its tag name cannot then be
reused.

## Google Play only

These gates are deferred for a GitHub APK prerelease and remain mandatory for Play.

- [ ] Data Safety, foreground-service, content rating, target audience, ads, app access, and store listing declarations are submitted and reviewed.
- [ ] Play App Signing, upload-key ownership and recovery, permanent cross-channel app-signing identity, and signing-certificate records are complete.
- [ ] Foreground-service evidence video and declaration cover video, Mokuro image, offline-TTS/local-audio, no-media, cancellation, timeout, and notification-denial behavior.
- [ ] Pre-launch report and internal then closed-track soak pass; crash/ANR and user-feedback thresholds are defined.
- [ ] Play staged rollout, halt criteria, rollback owner, and store-specific legal review are approved.
