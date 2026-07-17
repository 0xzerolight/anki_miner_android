# External and manual gates

Automation cannot establish the following results. Record the exact commit, artifact, resource publications, devices, AnkiDroid version, operator, UTC time, and evidence hash for each run.

## Physical device

- [ ] Selected S1a tokenizer publication passes the full parity corpus on a supported ARM64 device and produces the exact acceptance receipt required by release packaging.
- [ ] Three clean runs on a representative mid-range ARM64 device meet the defined cold-initialization target and peak-memory ceiling.
- [ ] Representative novel/reading tokenization throughput and storage use are recorded.
- [ ] Real local video/subtitle mining creates a correctly rendered AnkiDroid card with verified audio and screenshot.
- [ ] TXT/Aozora, reading-subtitle, EPUB, and Mokuro-plus-archive runs each create a correctly rendered AnkiDroid card; EPUB covers and Mokuro page images are verified where present.
- [ ] Reading with an installed offline Japanese voice creates playable sentence audio; a missing or failing offline voice leaves the card usable and reports the retained warning.
- [ ] Imported dictionary, frequency, pitch-accent, known-word, bundled-wordset, and local expression-audio sources affect cards in the configured order without remote media fetches.
- [ ] Cancel during probing, extraction, media insertion, and note insertion produces an accurate result without blind retry or leaked work.
- [ ] Screen-off/background processing, platform FGS timeout, process kill, low storage, non-seekable provider fallback, and recovery are exercised.
- [ ] Empty selections and text-only reading without media options finish without an FGS; media work remains usable after notification permission denial and is visible in Android's Task Manager.

## User experience and compatibility

- [ ] First-run setup, resource download/import/repair, offline and corrupt-resource errors, and custom Yomitan import are understandable and recoverable.
- [ ] AnkiDroid absent, uninitialized, permission-denied, incompatible, force-stopped, and upgraded cases are tested.
- [ ] Jisho remains off by default and its disclosure appears before opt-in; offline-only behavior is confirmed after disabling it.
- [ ] Rotation, process recreation, navigation, dark theme, large text, screen reader labels, empty states, and destructive/retry actions are reviewed.
- [ ] API 26 and current API 36 behavior, 4 KiB and 16 KiB page-size devices, and intended ARM64 ABI coverage are recorded.

## External release systems

- [ ] Privacy policy and contact are publicly reachable.
- [ ] Data Safety, FGS, content rating, target audience, ads, app access, and store listing declarations are submitted and reviewed.
- [ ] Play App Signing/upload-key ownership, recovery, least privilege, and signing-certificate records are complete.
- [ ] Pre-launch report and internal then closed-track soak pass; crash/ANR and user feedback thresholds are defined.
- [ ] Legal/source package, notices, support process, staged rollout, halt criteria, and rollback owner are approved.
