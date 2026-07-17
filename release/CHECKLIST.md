# Release checklist

Complete this list for the exact signed candidate and store the reviewed copy in its release-record directory.

## Source and identity

- [ ] Version code is monotonic and version name is final.
- [ ] Git commit and tree are recorded; the build checkout was clean.
- [ ] Engine revision, runtime-wheel build key, tokenizer publication key, and physical acceptance receipt are exact and recorded.
- [ ] Corresponding-source archive was rebuilt from a clean environment, published, and hash-bound to the candidate.
- [ ] Independent legal review approved the GPL/LGPL, notice, source, relinking, and installation-information package; reviewer and scope are recorded.

## Artifact

- [ ] Release build, lint, unit tests, native/runtime artifact gates, and required connected acceptance lanes passed under the repository host limits.
- [ ] Signed AAB identity, certificate, size, and SHA-256 were independently verified.
- [ ] Base delivery remains within the current Play size limit; UniDic and other external resources are absent.
- [ ] Only intended release ABIs and components are present; debug probes, test fixtures, and development fallback APKs are absent.
- [ ] Every native ELF passes the 16 KiB page-alignment, ABI, PIE/dynamic-dependency, and license inventory gates.
- [ ] R8/resource shrinking passed; mapping and native debug symbols were archived privately for support.

## Privacy and Play

- [ ] Hosted privacy policy is final, reachable without login, linked in-app and in Play Console, and has a monitored contact.
- [ ] Data Safety review in [DATA_SAFETY.md](DATA_SAFETY.md) matches the exact artifact and Play form.
- [ ] `mediaProcessing` FGS declaration and evidence video in [FOREGROUND_SERVICE.md](FOREGROUND_SERVICE.md) were accepted.
- [ ] Store listing, content rating, app access, target audience, ads, and all other App content declarations were reviewed.
- [ ] Pre-launch report and internal/closed-track checks passed with no unresolved blocker.

## Manual and operational

- [ ] Every required physical-device and user-flow gate in [MANUAL_GATES.md](MANUAL_GATES.md) passed.
- [ ] Upgrade, uninstall/reinstall, storage pressure, offline behavior, process death, and recovery behavior were exercised.
- [ ] Rollout, rollback, support, vulnerability intake, and source-retention owners are named.
- [ ] Changelog and supported-version information are updated.
