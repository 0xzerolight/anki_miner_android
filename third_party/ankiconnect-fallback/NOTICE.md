# AnkiconnectAndroid fallback probe

AnkiconnectAndroid 1.15 is pinned only as the S2 fallback capability probe.
The Android port's production Anki seam is AnkiDroid's raw ContentProvider;
this project does not ship or embed the fallback APK and does not implement a
second exporter against its HTTP API.

The immutable release identity is recorded in `manifest.json`. The upstream
source is licensed under GPL-3.0-only. The APK must be downloaded from the
recorded release URL and verified locally before a probe; a mutable release URL
is not accepted.
