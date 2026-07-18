# AnkiDroid API notice

Anki Miner Android includes source from the AnkiDroid API module:

- Upstream: `https://github.com/ankidroid/Anki-Android.git`
- Tag: `v2.24.0`
- Commit: `ebcf8e0e34921628b9b8a496c66ffd4adbb3705f`
- Component license: GNU Lesser General Public License v3. Files which say
  "or later" are recorded as such; the two headerless basic-model helpers are
  conservatively recorded as LGPL-3.0-only. `FlashCardsContract.kt` carries its
  own permissive upstream notice.

The upstream copyright and license notices remain verbatim in each generated
source file. SPDX expressions for individual files are recorded in
`manifest.json`. The non-standard permissive notice is recorded as
`LicenseRef-AnkiDroid-FlashCardsContract-Permissive` and remains at the top of
that source file.

Complete license texts:

- `upstream/api/COPYING.LESSER` — LGPL-3.0 text
- `upstream/COPYING` — GPL-3.0 text incorporated by LGPL-3.0

The hand-owned `BuildConfig.kt` compatibility shim is not represented as
upstream AnkiDroid source and is excluded from the provenance manifest.
