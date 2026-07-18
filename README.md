<h1 align="center">
  <img src="https://raw.githubusercontent.com/0xzerolight/anki_miner/main/anki_miner/gui/resources/icons/anki_miner.svg" height="76" align="absmiddle" alt=""> Anki Miner for Android
</h1>

<p align="center">
<a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
<a href="https://github.com/0xzerolight/anki_miner_android/releases/latest"><img src="https://img.shields.io/github/v/release/0xzerolight/anki_miner_android.svg" alt="Latest release"></a>
<a href="https://github.com/0xzerolight/anki_miner_android/releases/latest"><img src="https://img.shields.io/github/downloads/0xzerolight/anki_miner_android/total.svg" alt="GitHub downloads"></a>
<a href="https://developer.android.com/tools/releases/platforms"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+"></a>
<a href="https://github.com/0xzerolight/anki_miner_android/stargazers"><img src="https://img.shields.io/github/stars/0xzerolight/anki_miner_android?style=social" alt="GitHub stars"></a>
<a href="https://discord.com/invite/aDtQyZzUVP"><img src="https://img.shields.io/discord/1517634859110240326?logo=discord&logoColor=white&label=Discord&color=5865F2" alt="Discord community"></a>
</p>

<p align="center">
Turn native Japanese content into Anki vocabulary cards, on Android through AnkiDroid.
</p>

<p align="center">
Please leave a ⭐ star if Anki Miner helped you - it helps others find it :).
</p>

## Tabs

- **Video** - mine a video + subtitle pair, with a screenshot and audio clip on every card.
- **Reading** - mine manga (mokuro), novels (`.epub`, Aozora `.txt`), or standalone subtitle files.
- **Settings** - Anki, dictionaries, audio, frequency, filtering, UI. A skippable onboarding wizard walks the first run (tokenizer, dictionary, AnkiDroid).

## Other Features

- Extensive filtering: i+1, frequency limits, blacklist, wordsets, and more.
- Offline Yomitan dictionary import - definitions, pitch accent, frequency - chained by priority.
- Word audio from local audio packs; sentence audio from your device's offline Japanese text-to-speech.
- Optional Jisho.org online fallback for definitions (slower, rate-limited).
- Proper-noun filtering from bundled name wordsets.

<details>
<summary><strong>How It Works</strong></summary>

1. **Read the subtitles or text** and split Japanese into individual words.
2. **Filter** to content words you don't already know.
3. **Grab a screenshot and audio clip** from the video for each line.
4. **Look up definitions** in your configured offline dictionaries, optionally falling back to Jisho online if enabled.
5. **Send the finished cards to AnkiDroid.**

</details>

## Installation

### Requirements

- **AnkiDroid** installed from the [Play Store](https://play.google.com/store/apps/details?id=com.ichi2.anki) or [F-Droid](https://f-droid.org/packages/com.ichi2.anki/).
- A 64-bit ARM (`arm64-v8a`) device on Android 8.0 (API 26) or newer.

1. Install **AnkiDroid** and open it once.
2. Download `anki-miner-android-<version>-arm64-v8a.apk` from the [latest release](https://github.com/0xzerolight/anki_miner_android/releases/latest).
3. Allow installing from unknown sources, then open the APK to install.
4. Optionally verify the download against the published `SHA256SUMS`.

The app does not update itself; install a newer APK over the old one to update.

## Recommended Resources

| Type | Resource | What you get | Add via |
|------|----------|--------------|---------|
| Dictionary | [Jitendex](https://jitendex.org/) | JMdict successor; structured formatting, examples, tags | Onboarding wizard, or Settings -> Dictionaries |
| Dictionary | [JMdict](https://github.com/yomidevs/jmdict-yomitan) | Plain glosses; smaller, faster to index | Onboarding wizard, or Settings -> Dictionaries |
| Pitch | [Kanjium](https://github.com/mifunetoshiro/kanjium) | ~124k pitch-accent patterns | Settings -> Dictionaries -> Pitch Accent File |
| Frequency | [JPDB v2.2 Kana](https://github.com/Kuuuube/yomitan-dictionaries) | All-round default for media | Settings -> Filtering -> Frequency List File |

Proper-noun filtering uses bundled name wordsets derived from [JMnedict](https://www.edrdg.org/enamdict/enamdict_doc.html) (JMdict/EDICT project, EDRDG, CC BY-SA 4.0).

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Cards not reaching Anki | Install AnkiDroid and grant Anki Miner permission when prompted. |
| APK won't install | Enable install-from-unknown-sources for your browser/file manager; the device must be `arm64-v8a` on Android 8.0+. |
| No definitions found | Add a dictionary in Settings -> Dictionaries, or enable the Jisho fallback (slower, rate-limited). |
| Setup notice on a mining tab | Open Settings and finish the flagged step (tokenizer, dictionary, or AnkiDroid). |
| Sentence audio missing or wrong | Install a Japanese text-to-speech voice in your Android system settings. |
| Subtitles out of sync | Use the subtitle offset control on the mining screen. |

## Privacy

Offline-first: no accounts, analytics, or tracking. The app only reaches the network for optional Jisho.org lookups and one-time resource downloads (e.g. the UniDic tokenizer), always over HTTPS. See [PRIVACY.md](PRIVACY.md).

## Contributing

Contributions of any kind are welcome.

- Build from source and dev setup: [CONTRIBUTING.md](CONTRIBUTING.md).
- Architecture overview: [ARCHITECTURE.md](ARCHITECTURE.md).
- Bug reports and feature requests -> [Issues](https://github.com/0xzerolight/anki_miner_android/issues).

Related project: the desktop [Anki Miner](https://github.com/0xzerolight/anki_miner).

## License

GNU General Public License v3.0-or-later. See [LICENSE](LICENSE); bundled third-party components are listed in [NOTICE.md](NOTICE.md).
