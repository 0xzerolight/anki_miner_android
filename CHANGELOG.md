# Changelog

All notable project changes will be recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases will use semantic versioning once a public version exists.

## [Unreleased]

### Fixed

- Local audio stopped being added to cards for any word that had been mined before. The first time a word was mined its audio attached normally; every time after that the card was created silently without it, with no error and nothing on screen to explain it. The app was reserving each media filename permanently, so the second attempt asked for a name the first one still held. Only audio from local packs was affected, because it is the one file that is byte-for-byte the same every run — screenshots and sentence audio are re-extracted each time and never collide. Words already blocked this way work again on the next run; nothing has to be reimported or reset.

## [0.4.1] - 2026-08-05

### Fixed

- Animated screenshots no longer fail every word on most phones. The AV1 encoder in the bundled ffmpeg was built assuming the newest ARM instructions exist on every device, so on anything below the 2022 flagship generation the encoder crashed on its first frame, every word lost its clip, and the run ended with "Media extraction failed for all words". The encoder now checks the CPU at runtime and uses the fastest instructions the phone actually has — newer phones keep their speed, older phones now work.

- The audio collection was still rejected at import, this time by an index-file ceiling. The nhk16 pack's `entries.json` is about 42 MB and the importer capped JSON members at 32 MB, so picking the archive failed immediately — reported as "The archive holds more data than this device can import" however much free space the device had. The ceiling now clears the real collection with room to spare, and an archive the importer does refuse is named by what actually tripped: a single oversized file, too many files, or expanding past the size an audio pack may reach — free space has its own message and is only blamed when it is the cause.

## [0.4.0] - 2026-08-05

### Added

- Audio can be mined on its own. A new Audio tab takes an audio file and a transcript and runs the same pipeline the video lane does, with the audio-only word curator playing the line rather than showing a frame. A note type that maps Picture but not Audio is called out before the run starts, because that is the one configuration where an audio run keeps a word only if the file carries embedded cover art.
- Reading material can be pasted rather than picked from a file. The reading screen takes either a document or pasted text, and switching between the two keeps both, so a paste is not lost by looking at a file. The paste is held in memory for the life of the screen only and is never written to saved state — pasted text can be a password.
- The card image can be a short looping clip of the line rather than a single frame. It is off by default, and turning it on exposes clip length and quality. Clips are slower to mine than a still and the media is several times larger, which the setting says plainly. Android stores the clip as AVIF where the platform can name that format and WebP everywhere else — the app decides, because a file the platform cannot name would be stored by AnkiDroid as an unusable `.bin`.
- The word curator's video preview carries its own software decoders, so files the phone's hardware cannot play — 10-bit anime H.264, 10-bit HEVC, VP9 profiles, DTS/TrueHD audio — play in the preview just as they mine. Hardware decoding is still preferred where it works.

### Changed

- The word curator was rebuilt around the list. A word row now reads as a word above its own metadata instead of one run-on line; the heading, search, filter, sort and bulk actions are pinned above the list as a single block rather than scrolling away with it and costing a fifth of the screen; the screen opens with nothing expanded and a header collapses again on a second tap; an expanded word renders as one continuous card; the mined form is highlighted inside each example sentence rather than repeated as a label above it; a re-sort returns to the first result instead of jumping to wherever the old row landed; and at large font scales the video preview folds away so the controls stay reachable.
- Importing a resource asks for the file first and works the rest out. Known-word lists no longer ask for a format — the parser already detects JSON, CSV, TSV and plain text and reports what it found, and the two entry points that disagreed on this now behave the same. Frequency and pitch imports derive their display name and format from the document that was picked, rather than demanding both before the picker will open. An audio pack derives its internal ID from the archive itself, so a pack no longer has to be named by hand before it is opened.

### Removed

- The permanent Re-check action on the note type is gone. Note type, field mapping, card type and marker edits already re-verify the target, and an inline Retry still appears when a target check actually fails.

### Fixed

- Local audio packs could not be imported from the collection people actually download. That collection is a single `.tar.xz` holding jpod, nhk16, shinmeikai8 and forvo: the picker greyed the file out entirely, the importer accepted only a ZIP containing exactly one pack, and its member ceiling sat below the collection's own entry count. Archives are now read as tar or ZIP whichever way they are named, an archive holding several packs asks which one to take and extracts only that pack, and a rejection says which of the three things went wrong instead of one message for all of them.
- A large audio-pack import no longer dies when you leave the app. It runs under a foreground service holding a wake lock, because the import has no resume — an interrupted one starts again from zero. The import itself is also considerably faster: it no longer forces a disk sync for every one of a hundred thousand media files, nor resolves the full symlink path of every row in the pack index.
- Dictionary media in AVIF format was stored as `.bin` on every device, including ones that handle AVIF. The format a media file is saved under is now decided from the device's own capabilities rather than fixed at build time.
- A video the device could not decode left the word curator's preview as a silent black rectangle — mining worked, the preview just showed nothing. The preview now names the codec the device cannot play and offers a retry, and a playback error no longer leaves the player dead for the rest of the run.
- A `.csv` frequency, pitch or known-words file was greyed out in the file browser on some devices, because Android types that extension as `text/comma-separated-values` and the import filters did not carry that spelling. The filters now accept text of any kind plus the spreadsheet types a wildcard cannot reach.
- A subtitle file the app cannot read was accepted when it was picked and only rejected later, after the video had already been copied. Both the picker and the run now apply the same rule, so the rejection arrives immediately.
- "Skip for now" in the setup wizard needed two taps: the button raised a confirmation the button itself already stated. It closes the wizard on one tap now, while System Back still confirms, since skipping is remembered and an accidental Back would suppress onboarding for good.

## [0.3.0] - 2026-08-03

### Added

- The word curator shows the dictionary entry for the word you are looking at, so you can decide whether it earns a card without leaving the screen. It reads the same offline dictionaries that build the card, never the network, and says so plainly when a word has no entry. Where a word has no entry of its own but its dictionary form does, the entry is labelled with the word it actually matched rather than quietly standing in.
- A word can be marked as already known from its row in the curator. Marked words drop out of the current run and are written to the known-words list when you confirm the review — cancelling at any point, on any page, writes nothing.
- Word and sentence can be copied from a curator row. The sentence copied is the one you have selected for the card, not the first alternative.
- The word curator keeps the video pinned above the list and seeks it to the sentence you are looking at, so a word can be judged in context instead of from its text alone. The player shows the subtitle line for the position it is at, can be collapsed when the list needs the room, and plays the copy of the video the run itself is cutting from. A source the storage provider will not let the app seek reports that the preview is unavailable and leaves the rest of the curator working.
- Subtitle timing can be overridden for a single run from the mining screen, leaving the default in Settings alone.
- Subtitle timing can be tested before mining starts: the video plays against its subtitle cues, the offset can be nudged in tenths of a second and compared against the unshifted timing, and the offset you settle on is carried into the run.

### Fixed

- The engine's notice for words dropped before the curator no longer reads "Skipped N words", which contradicted the run's own skipped-and-new count for words that never reached the curator at all.

## [0.2.1] - 2026-08-02

### Removed

- The duplicate-cards setting is withdrawn. It shipped in 0.2.0; mining skips a word that already has a note anywhere in the collection again, with no way to turn that off. The deck-scoped duplicate machinery the setting drove — the deck-scoped known-word scan, the exact-deck create scope, and the deck-scoped duplicate probe — is gone with it, so the collection-scoped scan is the only one left. Existing preferences shed the retired key on first launch.

### Fixed

- Words with several pitch accents no longer render the accents fused into one unreadable run in the pitch graph and pitch text fields; the accents are wrapped as a numbered list, matching Yomitan's own export. (#7)

## [0.2.0] - 2026-08-01

### Added

- Settings can build a redacted multi-file diagnostics bundle, save it as a ZIP, or send it through Android's share sheet. The bundle includes the app's own logcat records but never another app's logs.
- A verbose diagnostics logging setting raises the level for this app's own Kotlin and Python records only, and reverts itself after seven days so a switch left on cannot rotate away the run it was meant to capture.
- A diagnostics bundle records in its manifest that the log sink was disabled, and by what, so an empty log file can be told apart from a quiet one. The bundle also reports whether the shipped ffmpeg and ffprobe binaries answer, and summarises the warnings a run's engine raised.
- Subtitles can be filtered by a regular expression before mining, with the desktop's presets available as a starting point. Patterns are checked as they are typed against the same length and unbounded-repeat limits the desktop enforces.
- Bracketed subtitle annotations — speaker labels, sound effects — can be stripped before the text reaches the tokenizer.
- Word lists: a blacklist and a whitelist can be imported from a plain-text file and enabled independently. The file is validated as UTF-8 and kept by the app, so the engine re-reads it at the start of every run.
- Duplicate cards can be allowed explicitly, rather than always being skipped.
- Note types built on the JP Mining Note conventions can be marked as such, so the engine writes its marker field instead of guessing from field names. The conventional field is preselected when the note type has it.
- Pitch accent is a multi-source resource now, with its own priority chain, per-source validity reporting, and an identifier derived from the display name — so a second import lands beside the first instead of replacing it.

### Changed

- The vendored Japanese mining engine is re-pinned to current desktop, which brings the pitch accent source chain, the corrected Unicode normalisation for stored known words, and the fixes accumulated upstream since the previous pin.
- Mining progress composes each stage's fraction inside that stage's band, so the bar advances once from empty to full instead of restarting five times per run.
- Diagnostics records follow an eight-kind severity and volume taxonomy: INFO is limited to phases, user actions, and external batches; per-item detail is DEBUG-only. Exported Kotlin and Python logs share one line grammar, with TAB-prefixed throwable continuations.
- Python log timestamps now use UTC ISO-8601 with millisecond precision and a trailing `Z`, matching Kotlin logs so the two files sort into one timeline.
- Duplicate mode looks for known words in the target deck rather than across the whole collection, which is how the desktop scopes duplicates. The scan collects at most 100,000 notes from that deck, the same ceiling every other scan holds, and separately gives up on a deck tree wider than 1,000,000 cards: `deck:"Name"` reaches subdecks too, and their rows are read only to be discarded. Decks excluded from the scan are subtracted from it rather than counted into it, so they hold a budget of their own instead of consuming the target's; the whole walk is given the five-minute deadline that bulk reads get, not the thirty seconds an interactive read holds; and a collection over any of the three ceilings is reported as a condition of that collection, naming what it exceeded, instead of surfacing as "Unexpected error" with a stack.
- A run parked in curation releases its CPU wake lock while it waits for an answer and takes it again on confirmation, so a long read through the candidate list no longer holds the device awake for nothing. The foreground service stays up throughout.
- Checking a media batch for colliding filenames decides each pair by inspection and runs its full sweep only for a real collision, where the sweep's reason is needed. The sweep ran for every pair before, inside the write transaction and with every other writer waiting behind it.

### Fixed

- Staging progress is labelled and scaled as what it is. A copy reported its bytes as items in the notification and carried no unit at all on the reading screen, and once labelled, a subtitle file read "0.0 of 0.0 MiB"; the scale is now picked from the total, so small sources report in bytes or KiB. The notification and the mining screen share that choice, and redraws are coalesced to one per rendered percentage point instead of one per 256 KiB of copying.
- Resource imports over the storage picker bound how long the provider may stall rather than how long the whole transfer takes, so multi-gigabyte imports that failed after sixty seconds now complete. A provider that has genuinely stopped responding still aborts the import.
- Importing an audio pack with too little free space says so, instead of refusing the file against a one-byte size limit.
- A settings store that cannot be read no longer terminates the app at launch. Mining stays blocked with the reason it already reports, and Settings still leaves its draft unloaded so nothing on screen can overwrite recovered preferences.
- Resetting an interrupted run clears the record whichever screen wrote it. One record covers both lanes, so a reading crash left the mining screen refusing every start behind a reset button that did nothing, and the other way round.
- The AnkiDroid mutation journal deleted the same resolved remediation twice while pruning old records, and the resulting failure was raised from the database's open callback, where nothing catches it. Every later open replayed it, and the AnkiDroid connection stayed broken until app data was cleared.
- Diagnostics export no longer disables logging for the rest of the session when a snapshot fails, rebuilds its bundle when the staged file has since been pruned rather than failing every retry, and keeps redaction to user text: a record's own keys and enumerated values are sealed first, so a `key=` shape inside a quoted value can no longer rewrite the structure around it. An Activity recreation during an export opens one save picker rather than a second over the first.
- A word-list pick survives process death, and no longer displaces an import — or a pick for the other list — already queued and waiting on startup recovery.
- The app shell behind the onboarding wizard is inert. TalkBack could swipe past the wizard onto the navigation bar and activate it, and keyboard focus could search into the screens behind it.
- A resource operation cancelled while it was already committing reports plain cancellation, instead of a delivery failure offering to retry work that had finished.
- A media read that passes its deadline cancels its worker rather than leaving it blocked on an unresponsive provider for the rest of the session.
- Cards a filtered deck has borrowed from the target deck count as known. `deck:"Name"` reaches them, but they come back carrying the filtered deck's id, so a Custom Study session made its own notes look unmined and they were offered and written again as duplicates. The pre-insert duplicate check missed them for the same reason.
- A media asset left quarantined by an earlier run degrades that one row instead of failing the whole run. Recovery ran before the batch existed to degrade into, so the call after a quarantine died outright — the same failure as issue #6, one call later (#6).
- A note whose creation is rolled back fails on its own. A full or locked database ended the entire batch, where every other fault there fails one row.
- A settings reset that cannot be applied keeps its dialog open and says why, instead of closing having reset nothing and reported nothing.
- Speech synthesis uses any installed offline Japanese voice rather than only the system default, ignores voices the engine reports as not installed, and reserves "install a voice" guidance for a genuinely missing one — an engine failure or a slow start says that instead.
- Downloaded and cached expression audio is validated with the bundled ffprobe and bounded by one deadline across both the lookup and the download, so a stalled source cannot hold a run open indefinitely.
- A provider fault that cannot be attributed to a note now carries its stack into the diagnostics bundle. It carried only an opaque identifier, and the frame that identifier named is minified out of a release build, so the throw site could not be found from a bug report.

## [0.1.8] - 2026-07-29

### Fixed

- Pitch accent overlines covered every mora on note types that draw the mora box unconditionally and hide low mora with `border-color: transparent`, because the engine inlined a colour on low-mora lines and an inline style beats an author declaration. Nothing is declared on those lines now, so no note type's own styling can be defeated by it (#5).
- Mined media keeps its real extension for the remaining formats a run can produce: downloaded expression audio beyond mp3 and opus, every local audio pack format, Android speech synthesis output, and dictionary media such as pitch accent graphics. Two formats the platform cannot name a file after are staged neutrally rather than under a name that would still land as `.bin`, which extends the fix released in 0.1.6 (#2).
- Settings deep links land on the card they name; a conditional card appearing above the target no longer shifts it.

### Changed

- Sentence deduplication is off by default. It keeps one word per sentence and runs before curation, so it withheld candidates that were never shown.
- The Setup category is gone. The Japanese tokenizer card moved to Diagnostics and appears only when it is missing or failing, instead of a healthy install presenting itself as a fault report with a repair button.
- Installed bundled dictionaries and healthy resource inventories are hidden, leaving the dictionary, frequency, audio, and pitch cards to show only what is missing or broken. Inventories duplicated what the priority editor already listed; the broken case is the only one that added anything.
- A frequency import derives its identifier from the display name instead of asking for a lowercase slug.
- Replacing an existing resource is confirmed in a dialog raised before the import starts, rather than by a checkbox about a collision that was not visible and, on the pitch card, named an identifier that does not exist.
- The settings category tabs are denser, with fades marking the tabs off screen.

## [0.1.7] - 2026-07-28

### Changed

- The mining, Anki, and resource screens drop their explanatory prose and keep only what a control does not itself convey; headings name the screen they belong to, using the desktop application's terms.
- Settings rows are a single line with a 48 dp target, supporting text appears only while a field is invalid, and the eighteen-field note-type mapping collapses behind a summary that opens itself when the mapping is wrong.
- Passive containers are flattened: section, panel, and card borders are gone from groupings that carried no meaning, and six phase headings share one style instead of six.
- Spacing, layout, and motion come from shared tokens, action buttons are styled by what they do rather than all alike, and navigation transitions use the 150 ms/90 ms motion pair instead of the framework's 700 ms cross-fade default.
- The Japanese catalogue is complete apart from brands, file formats, and format-only templates; strings no source referenced are deleted from both catalogues together, and two "(s)" pseudo-plurals became real plural resources.
- The subtitle series field no longer performs a synchronous disk write on every keystroke.
- Candidate rows resolve their include and exclude labels once per composition, restoring the per-row scroll cost that splitting the row had regressed.

### Added

- Resource progress gains a finalizing phase for commit and publish work, and both progress models carry a unit, so term-bank counts no longer render as megabytes.
- Staging a file over the storage picker reports bytes copied, so a large video shows its copy progress instead of a static preparation message.

### Fixed

- The reading staging root sent the canonicalised cache path while the framework hands out the `/data/user/0` symlink, so every staged reading path failed the bridge's containment check and the run died encoding its own request. This affected text, EPUB, subtitle, and Mokuro sources alike, not only `.cbz` (#3, #4).
- Local audio packs were rejected by a fixed 2 GiB import cap that sat below every large pack. The cap is now free space, checked against the size the picker already reports rather than after streaming multiple gigabytes, and the archive reads in place, dropping the import peak from roughly three times the archive to twice.
- Archives that are too large, damaged, unsafe, or in an unsupported compression format report an actionable message instead of an internal code.
- Engine progress descriptions embed mined terms and were forwarded verbatim into the foreground notification, which the system can surface on a locked device. The text channel is removed rather than filtered, so no future call site can reintroduce it; notification bodies come from application resources and trusted counts.
- The whole candidate surface was a single checkbox-role toggle, so tapping a word to read its sentences excluded it. The row now opens the detail, the checkbox alone includes and excludes, and each is a separate 48 dp labelled target.
- Select all and deselect all silently spanned the entire page even while a search showed one row. The visible action is now scoped to what is shown and preserves hidden selections, with page-wide selection kept as a separately named action.
- Resource progress published a completed phase's size as the next phase's completed and total, so a 15 MB archive expanding into 170 MB sat behind a full, motionless bar. Counts are now phase-local and render as indeterminate motion until a phase has numbers of its own.
- Code shrinking renames application classes, so fault digests named a lambda and needed a mapping file to read. The digest now reports the topmost frame whatever its package, and bridge failures name the invariant that was rejected.

## [0.1.6] - 2026-07-24

### Changed

- The interface has been reworked end to end. A shared visual system (colour, typography, shape, spacing, and motion tokens) now drives every screen, the bottom navigation uses real icons instead of text in the icon slot, and all screens share one destination-aware app bar instead of each owning its own chrome and titles.
- Vocabulary curation is compact and navigable: candidates render as searchable, filterable, sortable rows; the whole candidate card toggles selection; only the focused candidate expands its sentence choices; and the sticky confirm/cancel actions stay reachable and stack full-width at large font scales or narrow widths instead of collapsing.
- Settings is split into categories behind a sticky tab row, composes only the selected category, restores each category's scroll position, validates each field in place with keyboard navigation, and renders resource or setup failures on their originating card rather than a detached snackbar.
- The onboarding wizard uses an inset-safe scaffold with fixed navigation and a step indicator, treats system back as "previous step", labels required versus optional steps truthfully, orders AnkiDroid setup before large downloads, and presents note-type mapping as a compact summary.
- Results lead with outcome counts and fold details away, and mining progress derives its title from the current stage instead of always reading "Adding notes to Anki".

### Added

- A `.cbz` or `.zip` whose `.mokuro` sidecar is contained inside the archive can be mined directly as the reading source, without a separate image archive.
- Known-word management moved to a dedicated screen that stays lazy and responsive with large lists.
- Restrained fade-through motion on coarse phase and step changes, animated progress and selection, and TalkBack pane-title and selection-count announcements on state changes.

### Fixed

- Screens hold their layout at font scale 2.0 and on narrow (480 dp) widths: no clipped or character-wrapped labels, no controls squeezed off screen, and adaptive controls fall back from horizontal to stacked or radio layouts when space is tight.
- Long deck names and readiness/status text wrap or ellipsize instead of being clipped.
- Mining file selections survive rotation and process death, with SAF access revalidated on restore and grants reconciled rather than released at startup.
- Generic "stopped unexpectedly" failures now carry a PII-safe digest (exception class plus first in-app frame, never the message or file paths) so opaque failures name where they came from.
- The wizard "all set" screen no longer shows a duplicate recovery action, and a single error is no longer rendered twice as competing banners.

## [0.1.5] - 2026-07-23

### Added

- Known-word management now supports previewed imports, search, removal, export, confirmed reset, excluded Anki decks, and bundled proper-name wordset selection.
- Setup gains a deck step that discovers existing AnkiDroid decks and offers an explicit create-or-use "Anki Miner" choice, instead of assuming a deck name.
- The selected note type is classified as writable and dedup-safe, useful, or fully enriched, with a nonblocking quality warning and a summary of which Anki field each mapped value writes to.
- Mining progress names its source (video, subtitles, or document) and includes the file name when the metadata is trusted, and mining screen titles carry heading semantics for TalkBack.
- A run that queried the `localaudio` server ends with a privacy-safe expression-audio summary — counts of unavailable, timeout, policy-rejected, oversized, malformed, and non-audio results plus fallback-pack hits, never any URLs.

### Changed

- Settings reset is split into confirmed mining-default, Anki-target, and resource-choice scopes. Each scope preserves unrelated configuration, and malformed stored settings recover per key instead of clearing the whole store.
- AnkiConnect-Android `localaudio` remains the primary expression-audio source, ahead of imported-pack fallback. Loopback directory access, JSON size, source count, URL length, redirects, total attempts, and redirect destinations are now bounded and policy-checked. A rejected or failed query still falls through to imported packs, and no request is made when the expression-audio field is unmapped.
- The four bundled proper-name wordsets are enabled by default on fresh installs. A one-time settings migration preserves existing users' effective choices, so upgrading never silently changes filtering.
- Settings text and number fields coalesce edits with a short debounce instead of writing on every keystroke; toggles and reordering still save immediately, and pending edits flush when the app stops.
- The curation confirm/cancel row is a sticky bottom bar that respects the keyboard and navigation-bar insets, and the post-run result summary is length-bounded on both video and reading.

### Fixed

- Two Anki Miner fields can no longer be mapped to the same Anki field. Duplicate destinations are rejected in Settings, at verification, and at the engine boundary, instead of letting one value silently overwrite the word field.
- Re-selecting the same note type leaves the field mapping unchanged, and switching to a different note type keeps every still-valid mapping, auto-fills only the rest without collisions, and reports which mappings were discarded.
- Mokuro image archives with a `.cbz` extension can be selected again. Most Android file providers report them as `application/x-cbz`, which the picker did not accept.
- Status-bar and navigation-bar icons follow the app's theme rather than the system theme and re-apply on theme change, so bar icons stay visible when the two differ.
- Dictionary selector chips wrap onto multiple lines instead of being clipped on narrow screens or at large font scales.
- File selections on the mining screens survive rotation and process death, with access revalidated on restore, and system insets are owned by a single scaffold layer instead of being applied twice.
- A failure to persist wizard completion is now recoverable, with a visible error, a retry, and a clearly labelled continue-for-this-session escape; pending interrupted Anki work stays visible in recovery even when AnkiDroid is absent or unreadable.

## [0.1.4] - 2026-07-20

### Added

- AV1 video support. The bundled FFmpeg now includes the dav1d decoder, so screenshots extract from AV1 releases (mkv, webm, and mp4) instead of failing every word.
- The engine writes a capped log file, and Settings gains a separate "Share engine log" action so extraction failures can finally be reported with their actual FFmpeg errors. The log can include selected file names; review it before sending — the existing tester diagnostics report stays codes-and-counts only.

### Fixed

- **Media extraction no longer fails for every word.** Selected videos are now copied into app cache before FFmpeg runs. Android's scoped storage blocked FFmpeg child processes from reopening the picked file's descriptor (`Permission denied` on `/proc/self/fd/N`), which made every audio and screenshot extraction fail on every device since 0.1.0.
- Cancelling a run while the video is still being prepared now ends it as "Cancelled" instead of "Mining stopped".

## [0.1.3] - 2026-07-20

### Added

- Expression audio now pulls from the on-device AnkiConnect-Android `localaudio` server by default, with your imported local audio packs kept as an ordered fallback. A run with `localaudio` and no packs still records expression audio.

### Changed

- Settings save automatically as you edit, matching the desktop app. The hard-to-reach Save button is gone; Restore defaults and the incomplete-number warning stay.

### Fixed

- Media (audio and screenshots) is no longer dropped with "N media file(s) could not be stored in Anki." App-data paths are now canonicalized on both sides so every media source matches the approved staging root.
- A dictionary, frequency list, or audio resource installed while Settings has unsaved edits now appears in its priority list immediately instead of only after a restart.
- Only Word/Expression is a required Anki field again. Sentence, definition, picture, audio, and the furigana fields are no longer wrongly mandatory and no longer block mining setup.
- Large Yomitan dictionaries that previously failed as "oversized" now import, and imports that fail on an unsupported compression method report an actionable, method-named error instead of a generic "corrupt" message.
- Import, resource, Anki, and save failures now appear as a snackbar instead of a card that could land off-screen higher up the Settings scroll and be missed.

## [0.1.2] - 2026-07-19

### Changed

- Anki setup now uses your own note type. Connect AnkiDroid, pick any note type you already have, and map Anki Miner's fields onto it — the same detect-and-map approach as the desktop app. Anki Miner no longer creates or manages a note type of its own.

### Fixed

- The Anki note-type setup no longer strands you on a dead "Resume verified setup" button when AnkiDroid cannot be read. Setup now guides you to connect AnkiDroid first, then select and verify a note type, and mining reports a clear, actionable reason when a note type is not yet ready instead of doing nothing.

### Removed

- First-party "Anki Miner" note-type provisioning, verification, and recovery. If an earlier build already created that note type, just select it during setup — no cards are lost.

## [0.1.1] - 2026-07-19

First published release; supersedes the internal 0.1.0 test build.

### Added

- Light/Dark theme setting (dark by default) in Settings → UI.
- JMdict (English) as a second pinned dictionary download alongside Jitendex; either, both, or neither can be installed.
- Skippable onboarding wizard (tokenizer, dictionary choice, AnkiDroid) replacing the mandatory first-run Setup tab; re-runnable from Settings → UI.
- Launcher icon ported from the desktop Anki Miner logo, with a monochrome themed-icon variant.
- Third-party license notices are bundled in the APK and viewable from Settings → Licenses → third-party notices.

### Changed

- Bottom navigation mirrors the desktop app: exactly Video, Reading, and Settings tabs, text-only, always enabled; the app starts on Video.
- All setup and resource management relocated into Settings, organised in the desktop section order (Anki, Media, Dictionaries, Audio, Frequency, Filtering, UI). Mining tabs show an inline readiness notice pointing to Settings instead of being disabled.
- Option pickers use Material segmented buttons instead of check-prefixed buttons.

### Removed

- The Setup tab and forced first-run flow.
- The legacy "Lapis" note-type migration path.

### Initial alpha implementation

Historical state: note-model provisioning below was superseded by
[0.1.2](#012---2026-07-19), which requires a user-selected existing note type
and permits only target-deck creation. The local-pack-only expression-audio
design was superseded by [0.1.3](#013---2026-07-20); current safety bounds are
recorded under [0.1.5](#015---2026-07-23).

#### Capabilities

- Android application shell, first-run setup, persistent settings, local video/subtitle mining, and TXT/Aozora, subtitle, EPUB, and Mokuro reading mining with paged vocabulary curation.
- First-party Anki Miner note model provisioning, ContentProvider note/media export, durable mutation recovery, explicit remediation, and notification routing back to the active run.
- Private dictionary, frequency, pitch-accent, known-word, proper-name wordset, and expression-audio resources with import, validation, recovery, attribution, and configurable provider priority.
- Offline Japanese Android TTS sentence audio for reading cards, with no network-voice fallback.
- Post-curation media-processing foreground execution with progress, cancellation, timeout handling, and a policy evidence checklist.
- Pinned Chaquopy runtime, synchronized Python engine, external UniDic installation, native tokenizer and FFmpeg tooling, golden parity fixtures, and host/emulator acceptance harnesses.
- Durable, bounded SAF staging for seekable and non-seekable providers, reading archives, and media inputs.
- GPL-3.0-or-later licensing with a bundled third-party NOTICE and an in-app privacy summary.
- Desktop-derived reading parity for Aozora, subtitle, EPUB, and Mokuro input,
  including packaged Android replay of a real reading card.
- Resumable resource downloads across process death, setup readiness refresh,
  long-list curation virtualization, and privacy-safe tester diagnostics.

#### Security

- Resource downloads require HTTPS and immutable size/hash verification.
- Custom resource parsing, archive extraction, bridge messages, callbacks, and card HTML are bounded and fail closed; remote dictionary media URLs are not permitted in generated notes.
- Media probe cancellation kills and reaps ffprobe/ffmpeg children.
