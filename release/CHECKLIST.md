# Release checklist

Complete every common item and the selected channel section for the exact signed candidate. Store the reviewed copy beside `release.json`. A GitHub APK prerelease does not require completed Play Console forms, but it does require the common legal, privacy, signing, physical-device, and source-distribution gates.

## Candidate and source identity

- [ ] Channel is exactly `github-apk-prerelease` or `google-play`; this checklist identifies which one.
- [ ] Version code is greater than every previously distributed build across both channels, and version name matches the immutable Git tag.
- [ ] Git commit and tree are recorded; the build checkout was clean and the tag resolves to that commit.
- [ ] Engine revision, runtime-wheel build key, tokenizer publication key, and physical acceptance receipt are exact and recorded.
- [ ] The acceptance receipt passes the repository verifier for this source and publication. The public record contains its SHA-256 and a redacted summary, not a personal device serial or machine-local path.
- [ ] The reviewed corresponding-source archive was produced from the exact source, published as a sibling of `release.json`, and hash-bound by the record.
- [ ] The final `release.json` is not embedded in the source archive whose hash it records.
- [ ] Independent legal review approved the GPL/LGPL, notices, source, relinking, and installation-information package; reviewer, scope, date, open issues, and decision are recorded.

## Common artifact and signing gates

- [ ] Host tests, lint, unit tests, native/runtime artifact gates, required emulator lanes, and required physical acceptance passed for the final source and inputs.
- [ ] The unsigned ARM64 `deviceRelease` APK passed the release artifact audit before signing.
- [ ] Zip alignment was completed before signing, and signing did not change the audited APK payload inventory.
- [ ] The signed APK passes `apksigner verify --verbose --print-certs -Werr` for its supported API range.
- [ ] Package ID, version code/name, minimum/target API, ARM64-only ABI set, byte size, SHA-256, and signing-certificate SHA-256 were independently verified from the signed APK.
- [ ] The certificate is the permanent app-signing certificate recorded before the first external binary; it is not a debug, temporary, or upload-key certificate.
- [ ] The private app-signing key remains offline, has tested encrypted backups, and was not placed in the repository, release assets, workflow secrets, self-hosted runner, logs, or command-line arguments.
- [ ] UniDic and other external resources are absent from the APK.
- [ ] Debug probes, test fixtures, Android test components, AnkiconnectAndroid, and development fallback artifacts are absent.
- [ ] Every native ELF passes the 16 KiB page-alignment, ABI, PIE/dynamic-dependency, and license inventory gates.
- [ ] R8/resource shrinking passed; mapping and native debug symbols were archived privately for support.

## Common privacy, user, and operational gates

- [ ] Hosted privacy policy is final, reachable without login, linked in-app, reviewed against the exact artifact, and names a monitored contact.
- [ ] Network behavior, Jisho opt-in, resource-host requests, local TTS, retention, deletion, and absence of analytics/crash reporting match the policy.
- [ ] Every required physical-device and user-flow gate in [MANUAL_GATES.md](MANUAL_GATES.md) passed on the exact signed APK.
- [ ] Installation over the previous public version, fresh installation, and uninstall/reinstall consequences were exercised and documented.
- [ ] Rollout, halt, rollback, support, vulnerability intake, signing-key custody, and source-retention owners are named.
- [ ] Changelog, supported-version information, installation guide, tester guide, known limitations, and feedback process match the candidate.
- [ ] No known unresolved security, privacy, data-loss, collection-corruption, or blind-retry blocker remains.

## GitHub APK prerelease

- [ ] `release/CHANNELS.md` identifies this as an alpha GitHub APK, not a production or Play release.
- [ ] `com.ankiminer.android` and the permanent certificate are registered or otherwise cleared under the current Android developer-verification requirements for intended testers.
- [ ] The custom release assets match the exact allowlist in [CHANNELS.md](CHANNELS.md); no AAB, unsigned APK, debug/test APK, raw receipt, key, private log, or user fixture is attached.
- [ ] `SHA256SUMS` covers the APK, certificate files, release record, corresponding-source archive, and notices bundle without creating a self-referential hash.
- [ ] The GitHub release remains a draft tied to the exact tag while assets and notes are reviewed, and immutable-release protection is enabled for publication.
- [ ] Release notes state supported API/ABI, tested AnkiDroid versions, APK and certificate hashes, manual update behavior, known limitations, open alpha objectives, and links to install, testing, privacy, source, and support information.
- [ ] A fresh user can follow [INSTALL.md](../INSTALL.md), initialize AnkiDroid, install the APK, complete required setup, and identify the installed version without developer-only knowledge.
- [ ] The draft was independently re-downloaded and its hashes, signature, source record, and installation were reverified before publication as a prerelease.
- [ ] The exact source and signed APK passed the disposable private-repository rehearsal in [MANUAL_GATES.md](MANUAL_GATES.md), including the protected publish workflow; its final redacted evidence hash is recorded in the artifact-bound approval document.

## Google Play only

These items may remain open for a GitHub APK prerelease. They are required before any Play testing or production track named by the privacy policy and Play rules.

- [ ] The permanent app-signing key was transferred to Play App Signing through the approved process, and a distinct upload key and recovery process are recorded.
- [ ] Signed AAB identity, upload certificate, app-signing certificate, size, SHA-256, and Play-generated delivery identity were verified.
- [ ] Base delivery remains within the current Play size limit and intended ABI/device filtering is correct.
- [ ] Data Safety review in [DATA_SAFETY.md](DATA_SAFETY.md) matches the exact artifact and submitted Play form.
- [ ] `mediaProcessing` foreground-service declaration and evidence in [FOREGROUND_SERVICE.md](FOREGROUND_SERVICE.md) were accepted.
- [ ] Store listing, content rating, app access, target audience, ads, and all other App content declarations were reviewed.
- [ ] Pre-launch report and internal then closed-track checks passed with no unresolved blocker.
- [ ] Production rollout, crash/ANR threshold, halt criteria, staged expansion, and rollback owner are approved.
