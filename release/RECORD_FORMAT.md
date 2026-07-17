# Deterministic release record format

Each candidate directory must contain `release.json` plus the reviewed Markdown declarations referenced below. Serialize `release.json` as UTF-8 JSON with sorted keys, two-space indentation, LF endings, and one trailing newline. Paths are repository-relative or artifact filenames, never machine-local absolute paths.

Required top-level keys are:

```json
{
  "artifacts": [],
  "declarations": {},
  "engineRevision": "40 lowercase hex characters",
  "gates": {},
  "releaseSchemaVersion": 1,
  "runtimeWheelBuildKey": "64 lowercase hex characters",
  "s1aAcceptanceReceiptSha256": "64 lowercase hex characters",
  "s1aPublicationBuildKey": "64 lowercase hex characters",
  "sourceArchive": {},
  "sourceCommit": "40 lowercase hex characters",
  "sourceTree": "40 lowercase hex characters",
  "toolchain": {},
  "versionCode": 1,
  "versionName": "0.0.1"
}
```

Every artifact entry records filename, byte size, SHA-256, ABI set, package/application ID, version, signing-certificate SHA-256, and whether signature verification passed. `sourceArchive` records the archive filename, SHA-256, byte size, and public URL. `declarations` records the SHA-256 and final status of the privacy policy, Data Safety review, FGS declaration, third-party notice review, and corresponding-source review. `gates` records command or manual procedure, UTC timestamp, operator, exact input identities, outcome, and evidence-file SHA-256. `toolchain` records the versions already pinned by repository scripts plus the bundle/signing tool versions actually used.

Do not place passwords, tokens, private keys, personal device serials, private filesystem paths, or user content in a record. A failed or waived gate remains present with its status and rationale. Only a record with no unresolved required gate may be associated with a published artifact.
