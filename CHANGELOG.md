# Changelog

All notable project changes will be recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases will use semantic versioning once a public version exists.

## [Unreleased]

### Fixed

- **Mining no longer fails on collections with long note fields.** The keyset walk that closed AM-007 kept the fixed 256-note page item count and treated the page's 256 KiB first-field byte budget as a failure: any 256-note ID window averaging over 1 KiB of retained first-field text raised `An Anki known-vocabulary page exceeds the v1 text limit` and aborted the run in phase 2, so the very collection the keyset walk unblocked hit the next cliff on sentence-length first fields. The budget now ends a page early the way the item count does — the row that did not fit is not consumed and opens the next page against a fresh budget, so a page carries up to 256 notes or 256 KiB of retained text, whichever fills first. No wire or schema change: every contract layer already accepted short continuing pages, and progress is guaranteed because the 64 KiB per-field cap sits below the page budget. Reported by the same first-run user as AM-007, the day after v0.10.1 shipped.

## [0.10.1] - 2026-08-23

### Changed

- **The offline dictionary test names dictionaries (Settings -> Dictionaries).** The slot chips and the result line showed the internal slot id — a title+revision slug that collapses to `prefix-<sha256[:8]>` past 64 chars, so an imported Japanese-titled dictionary rendered as a hex-ish blob (#14). Both now show the imported `index.json` title (`sourceName`, the field the dictionary panel rows and attribution screen already display), falling back to the slot id when a stale lookup result outlives its slot.

### Fixed

- **An Anki collection over 100,000 notes no longer refuses every run.** The known-word scan built a stable traversal by snapshotting every note ID in the collection into a list and paging by index into it, with a 100,000-note ceiling bounding that list; note 100,001 raised `Known-word filtering supports at most 100000 notes in an Anki collection` and the run died in phase 2. The count was of *notes*, not known words and not Japanese ones, so a long-time Anki user crossed it on unrelated decks, and the one control they had could not help — exclusions were subtracted after the ceiling check, and the `include_known_words` bypass has no Kotlin caller. The traversal now keysets on the note ID: each page issues one `_id > lastId ORDER BY _id ASC` query for its own IDs and first fields (replacing the snapshot plus a per-page `_id IN (...)` fetch), reads one row past the page to decide whether anything follows, and the cursor carries the ID to resume after instead of an index into a retained list. `knownTotalScannedNotes` leaves `anki_limits_v1.json`, the generated Kotlin, the schema, the wire `limits` object and the adapter's cumulative check; the per-page item, byte and cursor bounds are unchanged, and the excluded-deck row budget is the walk's only remaining total. Reported by a new user on their first run; recorded as `AM-007` in the 2026-07-30 audit and confirmed there.
- **A note whose cards sit in two excluded decks counts once against the excluded-deck budget.** Each excluded deck is a separate browser search, and a note with cards in two excluded siblings comes back from both; the budget counted rows, so it charged that note once per search and could refuse an exclusion set at a fraction of the bound. It now counts distinct notes. The collection-size ceiling used to bound this walk from above, which is why it mattered less before.

## [0.10.0] - 2026-08-22

### Added

- **Download recommended resources (Settings -> Dictionaries -> Add, and the setup wizard).** One action fetches the JMdict dictionary, the JPDB v2.2 kana frequency list and the Kanjium pitch accents, which desktop has offered since its own recommended-set download and Android had no counterpart for: frequency and pitch could only arrive through the SAF picker, so a new user had to find and sideload two files by hand. No new machinery — `resource_catalog_v1.json` gains `frequency` and `pitch` kinds plus a root `recommended` id array, and the batch reuses `PinnedResourceDownloader` and the existing `resource.frequency.import` / `resource.pitch.import` ops, which take an absolute path and cannot tell a downloaded file from a staged one. Both new pins are commit-pinned `raw.githubusercontent.com` URLs with a SHA-256 and a byte length, because the catalog is frozen and compared on both sides; the upstream JPDB repository states no licence of its own, which the attribution records verbatim. The whole set runs inside one operation, one foreground lease and one journal record: cancelling stops before the next member and keeps what already landed, and each member is caught on its own so one failure never aborts the rest.
- **Fill mappings from field names (Settings -> Anki).** Selecting a note type maps its fields; selecting the same one again returns the map unchanged (`AnkiFieldMapPolicy.merge` short-circuits on an unchanged note type), so a field map saved against an older keyword table kept its gaps for good — the update above fixes nothing for anyone already set up. The new action re-runs the keyword match over the selected note type: keys the table recognizes are overwritten, keys it does not keep whatever the user picked, and a manual choice colliding with a keyword match is dropped rather than duplicated. Mirrors desktop's "Auto-Map Fields from Note Type", which had no Android counterpart — `AnkiFieldAutoMap.autoMap` existed but had no production call site at all.
- **Diagnostics report which pitch fields have a destination** (`anki.pitch_fields_mapped`). "No pitch on my cards" splits two ways — no usable source, or nowhere to put the data — and only the first was answerable from a tester bundle, since `LogRedactor` redacts every mapped field name. The line carries our own logical keys, never the user's field names, so the redaction is intact.

### Changed

- **Jitendex has no install button any more.** The dictionary panel's per-dictionary Add entries and the wizard's per-dictionary cards are replaced by the single recommended-set action, whose dictionary is JMdict. Jitendex stays in the pinned catalog — existing installs keep their attribution, their panel row, Repair on a broken slot and the startup download reconcile — but the only remaining ways to put it in a slot are its own row's Repair and a Yomitan zip import. `installCatalogDictionary` is unchanged and still owns both.

### Fixed

- **Words stop failing with "media extraction failed — see log" when animated screenshots are on.** Both tester diagnostics bundles behind the Discord reports show every failing word timing out in the animated AVIF encode — `subprocess.TimeoutExpired` at the 60s budget, `audio_failures=0` throughout — with up to all six parallel workers inside libaom at once, so each encode got a fraction of the CPU and a stochastic subset blew the timeout (worst run: 20 of 58 words dropped; an animated failure drops the word because `keep` keys on the screenshot). Encodes now pass through a two-slot gate (`_ANIMATED_ENCODE_GATE`) so a paired encode keeps roughly its solo wall time while static screenshots and audio stay on the full pool, and the libaom command adds `-usage realtime` — measured ~4x faster at equal SSIM on the shipped flag set. Realtime rate control overshoots good-mode sizes at equal CRF, so the mapped CRF carries +12 in that branch to restore size parity. The timeouts were live since animated screenshots became reachable in 0.4.x; they *look* like a recent regression because per-word progress errors were decoded and discarded (`ProgressError -> Unit`) until 0.7.0 listed them in the run summary and 0.8.3 put the first cause on the failure card — visibility, not a rate change (the second bundle shows the same timeouts on 0.6.x-era runs).
- **The curation preview no longer fails mid-run with a file-not-found error.** The run's working copy (`saf-inputs/input-*.media`) lived under `cacheDir`, which Android may clear at any moment under storage pressure — and App Info → Clear cache does the same by hand. The copy runs to multiple GiB, which is exactly what invites the reclaim; once the file was gone, the next data-source open (any seek or resume) surfaced Media3's error 2005 in the preview, Retry re-prepared the same dead `MediaItem` forever, and the ffmpeg phases behind curation were doomed on the same path. Staged copies now live under `noBackupFilesDir` (the interruption store's and resource staging's home), which only the app deletes; the lifecycle is unchanged — deleted at run end, orphans swept at startup — and the startup janitor additionally sweeps the legacy `cache/saf-inputs` root so a pre-upgrade interrupted run cannot strand a multi-GiB orphan there.
- **Senren note types get pitch accent again, and Lapis/Kiku fill their category and source fields.** `AnkiFieldAutoMap.FIELD_KEYWORDS` was a stale copy of desktop `_FIELD_KEYWORDS`: desktop `466b2047` added the plural spellings the community note types actually ship, and Android never received it. Matching is exact after normalization (lowercase, strip spaces and underscores), so a missing spelling maps to nothing at all, silently. Senren spells all three of its pitch fields plural — `pitchPositions`, `pitchCategories`, `pitchAccents` — so the engine emitted no pitch data whatsoever and the note type drew no accent; Lapis, Kiku and JPMN lost `PitchCategories` and `MiscInfo` the same way, and every note type lost `frequencies`. All five spellings are back, restoring desktop parity. Existing setups need the new fill action above; a fresh note-type selection picks them up on its own.
- **The engine golden's pitch section runs again.** `engine_golden_v2_instrumented._pitch` still imported `PitchAccentService`, removed upstream when the per-source pitch chain replaced the single-file service, so the section raised `ImportError` on execution — invisible because the instrumented replay that owns it is on the API 26 lane's UNEXECUTED allowlist. It now builds the same shape mining does (imported per-source index behind `MultiPitchAccentService`), and a host test in the secretless job re-derives the section from the committed input and compares it with the committed expectation, so the module cannot rot unobserved again.

## [0.9.0] - 2026-08-20

### Added

- **Word audio imports straight from local-audio-yomichan's `android.db` (Settings -> Audio).** The desktop add-on's "Generate Android database" file — the same one AnkiConnect-Android reads — now imports like any audio pack. The engine has carried an `android_db` pack format (metadata `entries` table plus `android` blob table, entries and audio read in place from the registered database) since the pack framework landed, but Android had no way to register one. The picker, staging and bridge now recognize a bare SQLite file by magic bytes; the database is hard-linked (streamed-copy fallback) into the published slot beside a metadata-only `index.sqlite`, so a ~5 GB import costs no second copy and no extraction. One preflight candidate auto-imports, so the flow is the same two taps as a folder pack.

### Removed

- **The AnkiConnect-Android word-audio server is no longer queried.** Since 0.7.x every run probed `localhost:8765` (AnkiConnect-Android's local-audio server) as an unconditionally injected primary expression-audio source. Most users never had that app installed: each run burned connection failures until the circuit breaker opened and ended with an untranslated "localaudio not reachable" notice nobody could act on, since the app never named AnkiConnect-Android anywhere. Word audio now comes only from imported packs — the collection archives or the new `android.db` import, which serves the same audio the server did without a second app running. The bridge's ported URL fetcher (1112 lines), the loopback-origin policy, and the localaudio failure classes in the run summary are gone with it; nothing in the app performs loopback HTTP any more. The unreleased "unreachable vs server errors" summary split is dropped along with its subject.

## [0.8.3] - 2026-08-19

### Added

- README mining demo: a looping hero GIF of a full video-mining run (source pick → word curation → mining → 65 cards created → AnkiDroid review) plus the same run linked as an MP4 with sound. Recorded on-device with scrcpy at native 1080x2336 and cut with ffmpeg; the GIF speeds curation 10x, mining 100x and the card review 3x, while the MP4 keeps everything at real time except a 50x mining pass. Example-card GIFs are hotlinked from the desktop repo, since mining output is identical on both platforms.

### Changed

- **The interface says less.** A full pass over UI copy cut roughly eighty strings to terse phrasing across mining, curation, settings, the wizard and the privacy screens, in English and all eleven translations. The recurring shapes removed: reassurance tails ("nothing is removed from Anki, and no backup is kept", "Mining remains allowed"), mechanism explanations, tutorial tone ("come back here"), and empty states that direct the user to the screen they are already on. The failed-run card now leads with the actual failure message instead of a generic "this mining run stopped" sentence that hid the cause behind Details; the four recovery confirm dialogs share one body line ("Recorded as recovery evidence."), since each body restated its own button; the curation "selections from earlier pages are already saved" line is gone. Ten unreferenced string keys deleted from all twelve catalogs. License text, the Jisho data-safety disclosure's substance, and the two exact-pinned progress strings are untouched.

### Fixed

- **Mining no longer fails with "AnkiDroid returned an invalid or failed query" when an excluded deck has no notes.** AnkiDroid's browser-search URI reports a search with zero matches by returning no cursor at all rather than an empty one — verified live against AnkiDroid 2.24.0 stable — and the known-word scan treated that null as a failed query, so any deck ticked under Settings -> Anki -> "Exclude decks from known-word scans" whose search matched nothing (an emptied deck, an empty Default deck) killed every run about two seconds in (#13). A zero-match excluded deck now simply contributes no exclusions; every other strictness check on the scan is unchanged.
- **Mining no longer reports "no usable offline dictionary" while Settings shows one installed.** The Kotlin readiness gate accepted any occupied, schema-current slot, but the engine's preflight also requires the slot's chain entry to be enabled and the index to hold at least one entry — so a dictionary unticked in Settings -> Dictionaries (or left disabled by the pre-0.7.0 "Reset resource choices" button, whose damage the persisted chain preserved indefinitely) or an imported archive whose rows were all skipped passed the UI gate and killed the run seconds in. Readiness now resolves the same chain the engine builds; an occupied-but-blocked slot gets a "Review dictionaries" prompt naming the disabled-or-empty state instead of a misleading install prompt; and the bridge refuses a dictionary import whose usable entry count is zero before publication, so a failed replace leaves the installed slot intact.

## [0.8.2] - 2026-08-18

### Changed

- **Resource imports show a moving progress bar instead of a motionless "Importing...".** Dictionaries, frequency lists, pitch accent, audio packs, known words and the UniDic install now report real counts as they run - bank files for dictionaries, MiB for the rest - where downloads already had byte progress; dictionary imports get theirs bank by bank via an engine update.

### Fixed

- **A dictionary import keeps running when you leave the app.** The import ran without the foreground service audio packs already use, so on newer Android the cached process was killed at the background CPU quota about five minutes after leaving the screen - and a large dictionary needs far longer than that on a slower phone. With no resume, every attempt restarted from zero, so the import could never land (#13, tester bundle 2026-08-18: three `EXCESSIVE CPU USAGE` kills). Catalog installs, custom imports and the startup schema rebuild now hold the resource-import foreground service and its CPU wake lease; the rebuild's hold is best-effort so startup recovery still runs where a foreground start is refused.

## [0.8.1] - 2026-08-17

### Fixed

- **A Yomitan dictionary with a long Japanese title imports again.** The archive-derived slot id introduced in 0.7.0 encodes every non-ASCII character as a `uXXXX` token, so a title of eleven or more Japanese characters overflowed the 64-character slot bound and preflight reported the archive as "not a supported Yomitan dictionary" (#13). Overflowing slugs now truncate and carry a digest of the full slug; short titles keep their existing slot ids.
- **Dictionaries installed before 0.8.0 rebuild themselves at startup instead of blocking it.** The 0.8.0 engine re-pin moved the dictionary index schema, and the startup rebuild only covered frequency lists and pitch accent, so every upgraded install sat at "an occupied dictionary slot is damaged or stale" with no way out but deletion (#13). The retained `source.zip` beside each index is now re-imported in place, and a copy the current importer refuses degrades to the slot's replace prompt rather than wedging recovery.
- **Replace works on a broken custom dictionary slot while startup recovery has failed.** Both gates on the flow were silent: the picker result waited forever for a readiness that could never arrive - and swallowed every later Add or Replace click with it - and the import itself required a ready startup that only the replace could produce (#13). Row-scoped replacement now dispatches from the failed state and a successful replace completes startup recovery, matching the existing catalog repair path.

## [0.8.0] - 2026-08-17

### Added

- The diagnostics bundle carries per-thread CPU time as `system/thread-cpu.txt`, read from `/proc/self/task` and sorted by CPU descending. A tester bundle recorded three EXCESSIVE CPU USAGE kills with nothing that could name what was busy: `exit-reasons.txt` reports the kill but no thread, and the bundle's logcat only ever covers the session Export was pressed in.
- The repository carries a code of conduct and a security policy; vulnerability reports go to a private advisory rather than a public issue.

### Changed

- The mining engine is now the one behind desktop 2.11.0, five releases on from the version this app was built against. That version's own release notes describe an audit of thirty-four areas and more than a hundred and thirty findings, and the mining changes below all arrive with it rather than being written here.
- **Words are matched by the reading the dictionary attests, not the one the tokenizer guessed.** A word whose reading the tokenizer collapsed onto the wrong entry now takes the reading its dictionary row actually carries, and a genuinely ambiguous one is flagged rather than guessed at. Because the reading decides the pitch lookup, the audio filename and the furigana, this changes those too. Whole names are also rebuilt from the line before the proper-noun and name-list filters run, so a two-kanji surname is filtered as a name instead of as two unrelated kanji.
- **Re-mining a word already on a card no longer makes a second one when the existing card carries an invisible formatting character.** Anki's own duplicate check cannot see those characters, so a card written by another tool with one embedded was invisible both to the known-words filter and to the duplicate probe, and the run reported no duplicates while adding a copy.
- **Words written in kana are classed as kana.** A long vowel mark is filed in the katakana block, so すごーい and きれー counted as neither hiragana-only nor katakana-only and slipped past both exclusion switches; the same went for katakana stems with hiragana endings such as サボる and ヤバい. Ticking both boxes now means a kanji-only deck.
- **Two spellings of the same dictionary word produce one card, not two.** 肉じゃが and 肉ジャガ share an entry, and a run that met both used to ship both.
- Parallel media workers accept 1 to 20 rather than 1 to 32, which is the range the engine now enforces. A saved value above 20 is reported beside the field instead of failing partway through a run.
- Settings fits more on the screen. The status and save chips above the categories appear only when there is something to act on - a required setup task needing attention, or a save that failed and can be retried - and the text fields, buttons and the search field at the top of the screen are all shorter. Buttons are rectangular, matching the rest of the app.
- Dictionaries, pitch accent, expression audio and frequency lists are each managed as a single priority list, the way the desktop app presents them: every row carries its own enable switch and move up and move down controls, in place of the separate card per resource. The Jitendex install prompt left Settings for the setup wizard, where the first install happens; the dictionary add menu no longer offers a catalog install for a slot that is already filled. Settings search results for these resources carry the panel headings the screen now shows, rather than the titles of the cards they replaced.
- **Mining is not ready until a usable dictionary is installed.** The engine's `require_usable_offline_provider` raises before any work happens, but readiness never checked for a dictionary and the wizard listed the step as optional, so a tester reached a READY wizard with no dictionary at all and had three runs die two seconds in. The wizard step is required, the setup status card carries a dictionary row, and its action deep-links to the catalog.
- **Importing a large dictionary is faster and asks for less free space.** The bridge copied and re-compressed an archive Kotlin had already staged, doubling the peak footprint of a multi-hundred-megabyte import; it is hashed in place instead, and the defensive bank rewrite runs only for a pathologically large bank and writes stored rather than deflated when it does. The streaming bank reader also stopped copying its whole remaining buffer after every item, measured at ~18x the cost of compacting only once the consumed prefix passes half the buffer. Reserving space for a streamed archive that is never written had refused imports that fit: a 540 MiB dictionary demanded ~3.2 GiB free where ~1.9 GiB is enough.
- Settings fields open with the value the engine would use written in them, rather than standing empty with that value named underneath. Storage is unchanged: a field left at the default is still stored as inherit, so a re-pinned engine default keeps reaching a run instead of freezing at whatever the field showed, and a value already stored that equals the current default is rewritten as inherit the next time those settings are saved.
- A missing translation is reported by the localization gate instead of failing it, matching the desktop catalogs, where an untranslated entry ships and renders in English.
- The resource priority lists read in every supported language. The twenty-four strings this cycle added for them - the section headings, the ordering explanations, the add and repair actions, and the screen reader labels on the enable switch and the move controls - are translated in all eleven locales, so no catalog carries untranslated keys.

### Fixed

- **Installed dictionaries, frequency lists and pitch sources rebuild themselves the first time the app starts after this update.** All four resource index formats changed, and the engine only reads the current one, so every installed source would otherwise have stopped working - and a pitch source or dictionary in that state stops the app reaching its main screen at all. Each import kept the file it was built from, so the rebuild happens on its own with nothing to pick and nothing to re-download. A source whose original file is no longer there is reported as needing a fresh import.
- **Word audio is no longer fetched for a word whose reading is unknown.** A word the tokenizer left without a reading falls back to its own kanji, and the local audio server answers that anyway, so 辛い could be given からい audio on a つらい card and keep it from then on.
- **A frequency bank whose number is cut by a read boundary no longer fails the whole import.** JSON's number grammar stops before a trailing `.` or `e`, so `raw_decode("1.")` returns a value rather than raising, and the reader only refilled when a number ended flush against its buffer - a literal split across the boundary left the remainder looking like trailing garbage and the bank was rejected as invalid JSON. Integers were unaffected, so this reached the real dictionaries whose banks carry non-integer values.
- **A truncated read from the file picker is named as one.** `copyProviderInput` checked only that something had been copied, so a provider that ended the stream early produced a short but plausible file the engine could only call a corrupt archive; one tester bundle carried ten such dictionary failures with no way to tell the two apart. Reported-versus-staged bytes are now recorded for every archive stage, as the audio path already did, and a definite disagreement is rejected. A provider that reports no size at all stays "nothing to check", which is legitimate for cloud sources.
- **A setup failure no longer offers Retry.** Every engine exception collapsed to one retryable kind, so a tester who pressed Retry on a missing prerequisite - which cannot change mid-run - got the identical failure back. Setup errors classify separately and are absent from both repositories' retryable sets by construction.
- Stopping a reading run while the file is still being read reports it as stopped rather than as a failure.
- The in-app mining progress panel only moves forward; the notification still shows each cycle's own count. The bar floors at the highest fraction already shown, decode progress is clamped inside the band it belongs to, and a stage that completes fills its band, so a run reads 0 -> 100 without dropping back. Reading runs use the same floor.
- The search field on the word curator no longer clips its placeholder. Its height is a floor now rather than a fixed one, which also removes the branch that skipped the clamp above a font scale of 1.3.
- The third-party notices shown in the app name libwebp and libaom, the two encoders behind animated WebP and AVIF screenshots. Their license texts have been packaged alongside since the encoders were added, but the notice listing them was a hand-copy of the repository's NOTICE.md and was never updated when they landed. A host test now compares the two files and fails the build when they diverge.

### Removed

- The Strip annotations switch in Settings > Media is gone. Sound-effect captions, speaker tags and inline furigana are always removed from subtitle text now, so the switch described a choice the engine no longer offers.

## [0.7.0] - 2026-08-14

### Added

- Animated screenshots can span the sentence audio instead of a fixed length. Settings > Media has a Match audio length switch, which the desktop app has always offered and Android had pinned off; with it on, the clip covers the line's audio including its padding, and the Max clip length field beside it greys out rather than silently losing the argument. A value left out of range behind that disabled field no longer blocks every settings write.
- Every blank numeric and text field in Settings names the value it inherits, in small type under the field. Until now a blank field looked like nothing at all and the only way to learn what the engine would use was to type something and compare the results. The value disappears as soon as you type one of your own, and an error about the field takes its place while it lasts. A test fails the build if these drift from the engine's own defaults, so a stale one cannot quietly show a wrong number.
- The mining screens warn before a run that would silently produce cards without word audio: when an audio pack is installed but no note field is mapped for word audio, and when an imported pack is unusable and will be skipped. Both are advisories — mining still starts. Until now a pack could sit "installed" while every card came out without a pronunciation, with nothing anywhere saying why.
- The end-of-run expression-audio notice covers the local packs too. A pack whose index cannot be read any more, or one that is switched on but no longer loadable from disk, is named in the notice by its pack id; those failures previously went to a debug log and every lookup just returned nothing.

### Changed

- Cards no longer carry a made-up series name in their source field. The engine composes that field as `series — episode`, and on the desktop the series is the folder the file sits in; Android has no such thing, because the file picker hands over a display name and never a parent folder, so the app substituted the name of the lane you mined from and every card came out reading "Local video — ", "Manga — " and so on. The prefix is now stripped for the video, audio, manga and subtitle lanes before the card is built, so the field holds the file's own name. A file with no usable name leaves the field empty rather than filling it with the lane label alone.
- Card tags are one field now, and leaving it blank means no tags. It was a Tags box behind an Override note tags checkbox, which existed only to distinguish "not set" from "set to nothing" — a storage detail. The desktop app has a single Card tags field reading "Leave blank for no tags", and Android reads the same way. Anyone who had never touched the setting keeps `auto-mined`, and anyone who had deliberately cleared it keeps no tags.
- Importing another dictionary asks only for the ZIP. The Stable dictionary ID field was an Android-only invention: a name you had to make up, matching nothing you could see, before the file picker would open. The id now comes out of the archive's own title and revision, the way the desktop app does it. Replacing an installed dictionary moved to a Replace action on the row of the dictionary being replaced, which is where it was reachable from anyway.
- Diagnostics has one Share button instead of three export actions. The plain-text share was a strict subset of the bundle — the same report is inside the ZIP as `diagnostics.txt` — and the separate Save was a second route to a file the share sheet already offers to save. Settings > Diagnostics accordingly loses Share tester diagnostics and Save diagnostics bundle, and the privacy notice, which had promised a save-or-share choice, now describes what the app does.
- Four settings say what they actually do. Clip length is Max clip length, because the line's own length caps it; Subtitle timing offset is Default subtitle timing offset, because the per-file preview overrides it; the regex filter applies to subtitles rather than "video subtitles", since reading lanes use it too; and the settings-file description names what a saved file leaves out — installed resources, verbose logging, update preferences and setup progress.
- Controls that misreported their own state have been corrected. The bundled word-list switches showed themselves on with no file installed while the engine was being told they were off; the regex presets added a pattern without switching the filter on, so nothing happened; and three labels described behaviour the engine does not have.

### Removed

- Reset Anki target and Reset resource choices are gone from Settings > Reset. Neither did anything worth keeping: Reset Anki target cleared five fields that the setup flow asks for again anyway, and Reset resource choices did not restore a working configuration at all — it wrote every installed resource back as explicitly disabled, which left mining with no dictionary at all until each one was switched on again by hand. Restore mining defaults, which does work, stays.
- The Stable dictionary ID field is gone, along with Override note tags, Share tester diagnostics and Save diagnostics bundle. Each is described under Changed above; nothing they did is now unreachable.

### Fixed

- Spending a long time choosing words no longer loses the run. The foreground work that carries a mining run started when the run did, so the whole time you were reading through candidates it was burning the budget Android allows that work — and on a long session the system ended it before a single card had been written. It now starts when you confirm your selection. Two related failures went with it: a run that could not start that work at all sat there looking alive and never finished, and a run that had already got as far as writing cards could report itself stopped when the background service shut down.
- A finished run says what went wrong. Problems hit on individual words were collected and then discarded, so the summary listed nothing and a failed run showed a bare "mining failed" with no reason; reading runs threw away their per-passage errors the same way. Both are now listed in the run summary, and the first one is used as the failure message when the engine offers none.
- The word curator previews the audio the run will actually use. On a dual-audio file it played the English dub while the mined clip came from the Japanese track — with both default flags cleared, as most releases ship them, nothing was left to break the tie but the phone's own language. A Japanese track this device cannot decode used to play as silence, because a decodable dub hid the problem; it now says which codec is unsupported. Retry reloads instead of only clearing the message, and a failure during an audio-only preview shows a message and a Retry button at all.
- Japanese text matches however it was typed. A word entered with a combining dakuten rather than a precomposed character matched nothing: not in the curation search, not in the known-words search or manager, not in dictionary lookup, and not in an imported word list, whose entries were then never treated as listed. An imported known-words list had a second version of the same problem — variant kanji and other spelling differences meant words you already knew came back as candidates and became duplicate cards. All of these are normalised to one form now.
- A dictionary lookup that fails is retried. One failure marked the word as having no definition for the rest of the session, however many times you came back to it. An unusable reply from the online lookup was re-fetched on every later occurrence of the word instead of once per run. A lookup could also miss an entry that was in fact in the imported dictionary, and a word with no entry showed an error where it should have shown an empty definition.
- Select visible and Select whole page count only the words they can select. Both included words already marked as known, so the number on the button was larger than the number of words it selected, and the whole-page option could be offered when everything extra on the page was already known.
- An import that succeeded is no longer reported as failed. The dictionary, frequency, pitch, audio-pack and known-words imports all refreshed their list afterwards, and a failure in that refresh was announced as the import failing — with a Retry that ran the whole import again and installed it twice. The import now stays done, the message says the list could not be refreshed, and Retry only refreshes. The known-words preview also stayed on screen with the file still awaiting confirmation, so confirming again re-imported the same words; it closes as soon as the words are stored.
- Imports cannot be started twice or lost halfway. Tapping an import button twice opened two pickers and one of the imports silently did nothing. Rotating the phone or leaving the app while choosing which pack to take from a multi-pack audio archive lost the dialog and the entire import; the choice now comes back with the app. Abandoning that choice left the chosen location behind, so a later audio import could bring in the wrong pack. An import killed by the system left the file still shown as selected with its access still held; both are cleared at the next start.
- A multi-gigabyte archive is copied once, not twice. After you chose which pack to import from a multi-pack archive, the app copied the whole thing again from the picked file — a long wait, twice the space, and a plausible way to run out of it. The copy made while scanning the archive is reused.
- Cancel during a resource download takes effect immediately. It used to leave the transfer in place until the network gave up on its own, which on a stalled connection never happened, and then usually reported a network error rather than saying the download was cancelled.
- A long import no longer brings the app down. An import running past the limit Android sets for that kind of background work killed the app outright; it is now cancelled cleanly and reported. Starting another import just as one finished could remove the progress notification and let the device sleep, stalling the new import. An import that could not put up a notification at all ran anyway, unprotected, and could be killed the moment you left the app — it now refuses to start and says so.
- Running out of storage says so. Import failures blamed the file instead — "the pack's index could not be read", "import failed" — and building a diagnostics report gave a generic build failure. All of them now name the device's free space as the cause.
- Oversized and malformed archives are refused before they can do damage. A tar audio pack was unpacked until the running total crossed the limit, filling the device on the way; a zip declaring an enormous number of files was expanded in memory before any limit applied; a dictionary containing one unbounded record buffered it whole; a manga page-text sidecar with a huge internal structure was loaded entire; and an archive whose listing understated its own contents passed the size check and then stalled while opening. Each is now measured or counted first. A subtitle file too large to handle is likewise refused with a message rather than freezing the timing view or the curation player first.
- Archive problems are named rather than guessed at. A zip made with unsupported or password-protected compression failed with a generic error while the app was still reading its name, and now says to re-create it with standard compression and no encryption. A manga archive whose page-text sidecar — or the folder holding it — begins with a dot was refused as containing none; only operating-system junk entries are skipped now. An archive holding several sidecars silently used whichever sat at the top level, which could be the wrong text for those pages, and now stops and says there is more than one.
- A resource that will not load can be repaired or removed. An audio pack whose index became unreadable disappeared from the list entirely, so it could be neither seen nor deleted; it is listed as unavailable now. Reinstalling a bundled dictionary over a broken one did nothing at all. Deleting the broken resource made the startup banner go away while the word lists stayed unrecovered and abandoned downloads kept their space until the app was restarted, so the banner now clears only once the app has actually recovered. A frequency list the app could not fully read reported a failure and then turned up installed after a restart.
- An audio-pack import interrupted by power loss no longer leaves the pack listed with empty or truncated audio files inside it. The audio is flushed to storage before the pack is published, so a pack that appears is complete.
- Size and resource errors are shown in your language. The too-large message used English internal wording, formatted sizes with a dot whatever the phone's number format, and called a custom dictionary "resource".
- A settings file restores what it recorded. Any setting the saving device had never touched was left at whatever this device already had, so the restored configuration did not match the file; those are reset to their defaults now. The dictionary, frequency, pitch and audio-pack orders came back pointing at resources that are not on this device while installed ones were left out of the chain entirely — each saved entry is now matched to the equivalent installed resource, keeping order and on/off state, with anything ambiguous restored switched off and anything else installed added switched on. A corrupted or wrongly-encoded file used to apply its garbled text silently and is now reported as malformed. Exporting just after changing a setting wrote the previous values, and exporting or loading from a slow or offline cloud location hung indefinitely.
- Settings values that could not be entered, or that broke a run later. On a phone whose number format uses a comma, the numeric keypad types a comma and the field rejected it as an unfinished number, so the subtitle offset and its neighbours could not be typed at all. Typing anything non-numeric wiped the saved value back to the default rather than falling back to it. An overlong deck name, note type, field name or tag list saved without complaint and then failed card creation in the middle of a run. A subtitle filter with nested repeated groups was accepted and then froze mining on the first long line.
- Settings search and the jump-to-setting links land on the right card. A jump made while a search was active landed on the wrong one, and a second jump within the same category did nothing at all. A result whose card could not be located dropped you at the top of the category rather than leaving you where you were, and the target-deck result went to the wrong card. Search also offered settings that do not exist on this device — the dictionary lookup test with no dictionary installed, the tokenizer-data install when it is already installed, dynamic colour on older Android — and the theme entry was labelled with the wrong title. The buttons on the mining screens that send you to Settings now scroll to the card they are about instead of the top of the screen.
- Settings screens keep their state. The note type, card type, field mapping and marker-field choices stayed tappable while a save was still running, and tapping one did nothing visible. Rotating the phone, or returning after Android had evicted the app, closed the confirmation dialogs for the known-words reset, the settings reset and Anki recovery, quietly abandoning the action. The known-words manager said "no known words" while the list was still loading and while an import or removal was running, and offered no way to cancel that job; Remove deleted an imported word list with no confirmation at all. The button that unhides installed bundled dictionaries appeared only when the list was completely empty, so a mixture of installed and not-installed ones left no way back to reinstall one. Theme swatches are announced as radio options by screen readers.
- AnkiDroid is not declared missing when it is installed. An unexpected error during the check — as opposed to a clear answer that it is absent — reported it as not installed and offered an Install button; it now says the check did not complete and offers to try again. If access is withdrawn or AnkiDroid's external API is switched off mid-run, the run says which of the two happened rather than reporting a vague unavailability.
- Cards whose media file names contain punctuation are created. A dictionary image or audio file with a quote, colon, space or accented character in its name was refused outright and the run reported a media error. Acknowledging a leftover-media item in the Anki recovery list could also report a failure immediately after the acknowledgement had in fact been applied, leaving the item looking both failed and handled.
- The app's private storage stops growing run after run. The internal records for audio and images already attached to cards, and for media you had acknowledged, were never cleared, so space use rose with every run and never came back down. They are now cleared with the rest of a completed run's history.
- Word audio no longer collides between words or disappears for long ones. Two words whose text reduced to the same file name shared a single cached clip, so a card could be built carrying another word's pronunciation, and changing audio source kept serving the old source's clip; each word, reading and source combination now gets its own. A word or reading long enough to produce a file name the storage layer refused meant its audio silently never reached the card.
- A file picked from cloud storage cannot hang the app. A copy that stalled mid-transfer sat there preparing forever, with cancelling and leaving the screen having no effect and further attempts piling up behind it; it now gives up when data stops arriving, and cancelling ends it at once. After a read timed out, the next pick or import hung with no progress and no error because the abandoned read still held the only worker.
- Replacing a picked file releases the previous one. Swapping a video, subtitle or manga archive for a different file held on to access to the old one indefinitely, and past the per-app limit Android sets, newly picked files simply stopped being remembered.
- The timing preview uses the file you picked. Test timing stayed tappable while a replacement was still being opened, so the preview ran against the file being replaced; opening it just after a swap could show the previous file, or one no longer selected at all; and a file picked while the preview was open swapped it out from underneath. File picks are now ignored while the preview is up.
- An update check that fails is reported as failed. A server error or an unreadable answer said you were up to date and cleared an update it had already found. Unreadable update preferences made the app check even with checks switched off and re-offer a release that had been skipped. A check that kept failing — offline, typically — retried on every launch instead of once a day, and tapping Check for updates while the automatic check was running fired a second request rather than joining it. A storage write refused while saving the update preference could bring the app down.
- The progress display reflects the run in front of you. The phase indicator appeared at each new phase and vanished as soon as work inside it began, and the bar carried the previous phase's counts across the boundary. A phase announcement left over from a cancelled or earlier run could also land on the current run's display.
- One failed check no longer switches animated screenshots off for the whole session. A single hiccup while testing whether the device could produce them was remembered as a permanent answer and every later card silently got a still frame.
- Cancel stops a run promptly. Cancelling while a definition was being fetched online kept the run alive until that lookup finished — about ten seconds each, and far longer on a stalled connection.
- When the engine cannot start, the app says so. The mining screens claimed it was still starting up and offered a button that could not fix anything, and the wait never ended; they now say the app has to be restarted.
- The setup wizard waits for its own save. It could close while the settings were still being written and never report a failure, so the settings were quietly not applied.
- Pasting a large amount of text into the curation search box or the settings search box no longer crashes the app when the screen is rotated or the app is restored from the background.
- Dictionary definitions copied onto cards no longer carry clickable links out to the web. The wording is unchanged; only the link target is removed.
- Building a diagnostics report cannot hang. A device-log capture that stopped responding never finished; it is now cut off at its time limit and the report completes without the log tail.
- A local audio server that stops answering no longer stalls the run for its full 30-second budget on every word. After three words fail in a row on timeouts or refused connections the run stops asking it and goes straight to the installed packs; audio already fetched keeps being served, and the skips are counted in the end-of-run notice.
- Expression-audio fetches no longer rescan the entire audio cache after every successful download — on long runs the rescans compounded and also defeated the cache's own index, so each word got slower than the last.
- Picking a resource file from a provider that refuses lasting access — some cloud storage and file-manager apps do — now says exactly that and suggests picking the file again with the system Files app, instead of the generic "the resource operation did not complete". A provider that stops responding and an item that is not a readable document get their own messages too.
- An audio-pack archive in which no pack could be detected sometimes reported an opaque protocol error instead of saying no pack was found in it. A dictionary import that failed while staging the archive also described what it was copying as a generic "resource".

## [0.6.0] - 2026-08-07

### Added

- Installed dictionaries, pitch sources, frequency lists and audio packs can be removed. Each import card on the Settings screen now lists everything it has installed, with its slot id and entry count, and a Remove button beside each one; removing asks for confirmation first and deletes only from this device, never from Anki. Until now nothing could be uninstalled, so a file imported twice under slightly different names left two copies with no way to get rid of either. This is also the way out of a resource that will not load: a broken slot blocks mining and says so on startup, and removing it now repairs that without clearing the app's data. UniDic has no Remove button — the tokenizer is what the whole app runs on.

### Changed

- The long lists in Settings start folded away. A deck list is as long as your collection, and on a phone it buried everything under it and put a checkbox under your thumb on the way past. The decks to exclude from known-word scans, the dictionary, pitch, audio pack and frequency orders, and the bundled word sets each show a single line now — the name and how many of them are switched on — which opens the list when tapped. A list with nothing in it stays open, so the reason it is empty is still on screen.
- Dictionary definitions are drawn in the colours of whatever theme the app is set to. Until now they were rendered as the dictionary shipped them — black text on white — which on a dark theme meant a white slab in the middle of the screen, and a white flash on every word before it filled in. An imported dictionary's own styling of its entries is left alone, the way Yomitan leaves it, so the parts a dictionary colours on purpose still look like that dictionary. Applies to the definition pane while curating and to the preview in Settings.
- The mining screens give more of the phone to the words. The curation search box and filter row fold behind a chevron, Continue and Cancel sit side by side rather than stacked however narrow the phone is, the bottom navigation bar is shorter, and the tab screens no longer carry a title bar that only repeated the name of the tab already highlighted below it. Everything here reverses itself at large font scales, where the roomy layout is what keeps labels readable, and screens you reach from a tab keep their bar because that is where the back arrow lives.

### Fixed

- A dictionary definition longer than its pane could not be scrolled — the drag was taken by the word list underneath, so anything past the first few lines was unreachable. Definitions scroll on their own now, and the list still scrolls when the definition has nothing left to show.
- An audio pack downloaded as a `.tar.xz`, `.tar.gz` or `.tar` archive can be imported directly; only `.zip` worked before, and the packs are not distributed as `.zip`. Two ways of getting it wrong now say what happened instead of failing blankly: picking the `.torrent` file rather than the archive it downloads, and a file provider that hands back something other than the archive's own bytes — the second is answered by copying the archive to the device and picking it again. The import card names the formats up front.
- An audio-pack import interrupted while it was still examining the archive — the app killed, the phone rebooted — left its half-extracted working files on disk and no sign of them anywhere in the app. The interrupted import is now noticed on the next start, its leftovers are deleted, and it is reported with an offer to pick another archive.

## [0.5.0] - 2026-08-06

### Added

- The app speaks eleven languages. German, Spanish, French, Indonesian, Italian, Brazilian Portuguese, Russian, Vietnamese, Simplified Chinese and Traditional Chinese join English and Japanese, worded to match the desktop version so the two clients read alike. The app follows whatever language the phone is set to; on Android 13 and newer it also appears in the system's per-app language list, so it can be set to a language of its own. Notices produced by the mining engine itself are still English in every language.
- The app can tell you when a newer version has been released. Sideloaded builds have no update path, so once a day it asks GitHub whether a newer release exists and says so on the Settings screen. It never downloads or installs anything, a version you are not interested in can be skipped, and the whole check can be switched off in Settings > Diagnostics.
- The app carries the 29 colour themes the desktop version ships, and can follow the phone's own light/dark setting. Settings holds a light theme and a dark theme separately, so following the system swaps between the two you chose rather than between one fixed pair. On Android 12 and newer the app can instead take its colours from the wallpaper.
- Settings can be searched. Typing part of a setting's name — or of the explanation under it — lists the settings that match, in whatever language the app is showing, and picking one opens the tab it lives on and scrolls to it. Adding words narrows the list rather than widening it, and a setting whose name you typed is ranked above one that merely mentions your words.
- Settings can be saved to a file and loaded back. The file is plain JSON and carries everything on the Settings screen — the Anki target and field mapping, every filter and media option, and the order you put your dictionaries, frequency lists, pitch sources and audio packs in. It deliberately does not carry which resources are installed: on a new phone, import the packs first and the saved order attaches itself to them. Loading a file only changes what the file mentions, so a file written by an older version is safe to load, and anything it asks for that this version cannot use is skipped and counted rather than failing the whole load.

### Changed

- The default look is now the same indigo the desktop app opens with, so the two clients open looking alike. The teal the Android version used until now has been retired rather than kept under a name, so anyone who had not chosen a theme will see the new colours after updating; the theme list offers twenty-nine alternatives, several of them close to the old palette.

### Fixed

- Local audio stopped being added to cards for any word that had been mined before. The first time a word was mined its audio attached normally; every time after that the card was created silently without it, with no error and nothing on screen to explain it. The app was reserving each media filename permanently, so the second attempt asked for a name the first one still held. Only audio from local packs was affected, because it is the one file that is byte-for-byte the same every run — screenshots and sentence audio are re-extracted each time and never collide. Words already blocked this way work again on the next run; nothing has to be reimported or reset.
- A note-type field whose name contains a hyphen — `Sentence-Audio`, `Expression-Furigana` — was auto-mapped on Android but not on the desktop app, so the same note type produced two different starting field maps. Android now reads field names the same way the desktop app does. A hyphenated field is no longer filled in for you; pick it once from the field mapping and the choice is kept.

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
