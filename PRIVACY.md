# Privacy

Anki Miner for Android is an offline-first tool. It has no user accounts, no
analytics, no advertising, and no crash or usage telemetry. It does not send
your selected media or generated flashcards to an Anki Miner server. The
optional requests below do leave the device.

## What stays on your device

Everything you mine stays local: the video, subtitle, text, EPUB, and Mokuro
files you select; the vocabulary you curate; and the flashcards it creates. Cards
are written directly to AnkiDroid on the same device through its local
ContentProvider. Sentence audio uses your device's offline Japanese
text-to-speech voice.

## Network requests it makes

The app only contacts the network for these purposes, and only over HTTPS:

- **Dictionary lookups (Jisho.org):** when enabled, it sends the selected lookup
  term to jisho.org. Lookup terms can contain personal or sensitive text. The
  app adds no account identifier, but Jisho receives ordinary connection data
  such as the requester's IP address.
- **One-time resource downloads:** the Japanese tokenizer dictionary (UniDic) and
  any optional dictionary/frequency/pitch resources you choose are downloaded
  once from their public hosts (for example PyPI and the resource's own site) and
  stored in the app's private storage. Those hosts receive ordinary connection
  data. Downloads are size- and hash-verified.

It does not request access to your device's media library or contacts, and it
uses the Storage Access Framework so you pick individual files yourself.

## Diagnostics exports

Settings can build a diagnostics bundle only when you request one. The bundle
contains a README and manifest, the tester report, a redaction summary, current
and rotated Python and Kotlin logs, this app's own system-log tail, and recent
process-exit details. Entries may be tail-truncated or dropped to keep the
uncompressed archive within 6 MiB; the manifest records that status. It never
reads or includes another app's logs.

Log content is redacted with a fresh per-bundle salt before each archive entry
is written. Detected app paths, file/document identifiers, selected display and
series names, deck/note-type/field/tag text, Japanese text, and the build user
become tokens stable only within that archive. Run IDs remain so maintainers can
correlate one mining run across files. The bundle deliberately excludes
Build.SERIAL, SSAID, accounts, IP and MAC addresses, and package inventory;
AnkiDroid's version is the only peer-package lookup. You choose whether to save
the ZIP or send it through Android's share sheet; Anki Miner does not upload it
itself.

## Removing your data

Uninstalling the app removes its settings, downloaded resources, and private
data. Flashcards already written to AnkiDroid live in AnkiDroid and are managed
there.

## Contact

This is an open-source alpha. Report privacy concerns through the project's
public GitHub issue tracker. Do not include private media, mined text, or other
sensitive information in a public report; use the repository's
[private vulnerability reporting](https://github.com/0xzerolight/anki_miner_android/security/advisories/new)
for a concern which cannot be disclosed safely.
