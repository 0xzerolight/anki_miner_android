# Permanent APK signing identity

The first GitHub APK prerelease must establish the permanent signing identity
for `com.ankiminer.android`. Generate and back up that private key offline, then
sign every GitHub APK with the same identity. If Google Play is added later,
transfer this existing app-signing key to Play App Signing and use a separate
upload key.

Only the public certificate and its lowercase, colon-free SHA-256 fingerprint
belong in release assets. The keystore, private key, passwords, signing
properties, recovery material, and raw command logs must never enter this
repository, GitHub Actions, or the self-hosted runner.

After the owner completes the manual key ceremony:

1. Export the public X.509 certificate in PEM form.
2. Record its SHA-256 fingerprint in the protected repository variable
   `ANKI_MINER_APP_SIGNING_CERT_SHA256`.
3. Keep at least two encrypted offline backups in separate locations and prove
   one backup can be opened before the first prerelease.
4. Supply the PEM file to `scripts/sign-github-apk.sh` and
   `scripts/prepare-github-prerelease.sh`; it is copied into each release as
   `app-signing-certificate.pem`.

No placeholder certificate is committed because approving the real permanent
identity is an intentionally manual, irreversible release decision.
