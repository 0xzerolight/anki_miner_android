# UI audit capture rig

The rig is opt-in. Every test uses a JUnit assumption and skips unless the instrumentation
argument `uiAudit=true` is present. Commands below require the debug app and test APKs to already
be installed; they do not invoke Gradle.

## Screenshot suite

```bash
adb shell am instrument -w -r \
  -e uiAudit true \
  -e class com.ankiminer.android.uiaudit.UiAuditScreenshotTest \
  com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
```

The suite writes one PNG per screen-state, theme and font-scale combination
(currently 28 x 2 themes x 3 font scales = 168):

```text
/sdcard/Android/data/com.ankiminer.android/files/ui-audit/
```

Each filename has this form:

```text
<screen>__<state>__<dark|light>__fs<100|130|200>.png
```

Video, reading, and settings captures include production shell insets and bottom navigation.
Attribution and notices use the same shell but keep production behavior: their bottom navigation
is hidden. The first-run wizard is captured as its production full-screen surface, without bottom
navigation.

## Jank flows

Clear frame statistics before each run, sample `dumpsys gfxinfo com.ankiminer.android framestats`
from a second host process while the command is active, and use `UiAuditFlow` logcat `START`/`END`
markers as the sample boundaries.

### 200-candidate curation scroll

```bash
adb shell am instrument -w -r \
  -e uiAudit true \
  -e class com.ankiminer.android.uiaudit.UiAuditJankFlowTest#curationList200CandidatesScrollsBottomThenTop \
  com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
```

### Full settings scroll

This flow composes production category content with populated audit state. It visits every settings
category, verifies representative production card keys, then scrolls each category down and up.

```bash
adb shell am instrument -w -r \
  -e uiAudit true \
  -e class com.ankiminer.android.uiaudit.UiAuditJankFlowTest#settingsFullScrollsDownThenUp \
  com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
```

### Long reading-results scroll

```bash
adb shell am instrument -w -r \
  -e uiAudit true \
  -e class com.ankiminer.android.uiaudit.UiAuditJankFlowTest#readingResultsLongListScrollsDownThenUp \
  com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
```

### Wizard step-through

```bash
adb shell am instrument -w -r \
  -e uiAudit true \
  -e class com.ankiminer.android.uiaudit.UiAuditJankFlowTest#wizardStepsThroughEveryScreen \
  com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
```

No new dependency is required. The rig uses the existing AndroidX test, Compose test, and debug
test-manifest dependencies.
