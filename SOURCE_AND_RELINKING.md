# Source, rebuilding, and relinking

This document is engineering guidance, not legal advice. The app embeds GPL-3.0-or-later engine code and combines other GPL-compatible, LGPL, permissive, and data-license components. A qualified reviewer must confirm the licensing approach and release materials before distribution.

## Repository terms

Unless an individual file or vendored component states different terms, project-specific source in this repository is offered under GPL-3.0-or-later, whose canonical text is [LICENSE](LICENSE). Vendored components retain the terms identified in [NOTICE.md](NOTICE.md). No trademark permission or warranty is granted by the GPL text.

## Exact corresponding-source bundle

Publish a source archive beside each binary release and bind it to the signed artifact in the release record. At minimum it must contain:

- the exact tracked tree and commit used for the artifact, including Kotlin, Python, C/C++, resources, schemas, tests, and generated source;
- `tools/engine-sync/engine.lock`, its composition/override inputs, and the generated engine manifest;
- Gradle wrapper, version catalog, dependency locks, verification metadata, and all build scripts;
- runtime-wheel, tokenizer, FFmpeg, AnkiDroid API, MeCab, and code-generation recipes, patches, source locks, provenance manifests, and license texts;
- runtime/tokenizer publication identities, compiler/SDK/NDK/JDK versions, and the non-private build evidence available before the source archive is finalized;
- instructions and any non-secret material needed to rebuild, modify, relink, sign with a recipient-controlled key, and install the result.

The archive has one top-level directory and contains `anki-miner-source-manifest.json` and `anki-miner-external-source-inventory.json` directly under that root. Generate both from the clean annotated tag with `scripts/github_release.py write-source-manifest`. The command and release verifier require every tracked path, executable mode, and Git blob to match the tagged tree exactly. Every additional regular file is size- and SHA-256-inventoried, and every additional symlink is mode- and target-inventoried. Release tooling reopens the compressed archive and binds both inventories to the signed candidate's source, engine, runtime-wheel, and tokenizer identities. These mechanical checks do not replace the completeness and legal reviews above.

Signing keys, Play credentials, and private runner credentials must never be included. They must not be necessary to build and install a modified artifact under a different signature or application ID.

The final `release.json` is published beside the corresponding-source archive, not inside it: the record contains the archive's SHA-256 and therefore cannot be a member of that same hashed archive without creating a cycle. Publish both as immutable sibling assets and bind the APK, archive, notices, certificate, and record through `SHA256SUMS`.

Do not publish a raw physical-device receipt containing an ADB serial or machine-local path. The public record contains its SHA-256 and a reviewed redacted summary. The raw source-bound receipt remains private evidence available to the release reviewer and build verifier.

## Rebuild path

The supported host and exact commands are documented in [scripts/README.md](scripts/README.md). Repository tooling downloads only pinned inputs, verifies hashes, builds runtime/tokenizer publications, and runs `scripts/health.sh`. A production ARM64 variant is intentionally disabled until an exact physical-device tokenizer acceptance receipt exists.

Before release, prove that a recipient starting only with the published source bundle and public locked inputs can reproduce a functionally equivalent unsigned artifact. The rebuild must not depend on a publisher-only wheel directory, acceptance receipt, desktop checkout, or cached source archive. If an acceptance receipt must be regenerated, document the public procedure and supported device requirements.

## LGPL replacement and relinking

AnkiDroid API Kotlin source is compiled into the app's DEX output. Preserve its notices and complete LGPL/GPL texts, mark modifications, and provide the application source and build material needed to replace those files and rebuild the combined application.

The FFmpeg executables statically include FFmpeg libraries and optional codec libraries. The release bundle must provide the exact source, configuration, patches, build scripts, and whatever relinkable application material the applicable LGPL terms require. Demonstrate a clean replacement/relink build rather than relying only on source availability. Record the command and resulting hashes in the release evidence.

## Installation information

Android requires APKs to be signed. A recipient can sign a modified build with a key they control, then install it after uninstalling the differently signed package or after changing the application ID. Document the exact tested procedure for the release and confirm that no signature check, device lock, acceptance artifact, or distribution term prevents a recipient from running a modified build. Play App Signing credentials are not conveyed.

Whether these steps satisfy all GPLv3 installation-information and LGPL relinking obligations is a release-time legal-review question. Record the reviewer and decision without representing this document as legal clearance.
