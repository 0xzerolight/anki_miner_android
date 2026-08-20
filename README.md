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
Also on desktop - <a href="https://github.com/0xzerolight/anki_miner">Anki Miner for Windows, macOS and Linux</a>.
</p>

<p align="center">
Please leave a ⭐ star if Anki Miner helped you - it helps others find it :).
</p>


# <p align="center">Mining Demo</p>

<p align="center"><img src="https://raw.githubusercontent.com/0xzerolight/anki_miner_android/main/gifs/demo.gif" width="260" alt="Anki Miner for Android Showcase"></p>

<p align="center">⬇️ <a href="https://raw.githubusercontent.com/0xzerolight/anki_miner_android/main/gifs/demo.mp4">Full demo with sound (MP4)</a></p>

### Example cards

| ![ホント](https://raw.githubusercontent.com/0xzerolight/anki_miner/main/gifs/ホント.gif) | ![いちゃいちゃ](https://raw.githubusercontent.com/0xzerolight/anki_miner/main/gifs/いちゃいちゃ.gif) | ![代](https://raw.githubusercontent.com/0xzerolight/anki_miner/main/gifs/代.gif) |
|:--:|:--:|:--:|
| ⬇️ [MP4 (sound)](https://raw.githubusercontent.com/0xzerolight/anki_miner/main/gifs/ホント.mp4) | ⬇️ [MP4 (sound)](https://raw.githubusercontent.com/0xzerolight/anki_miner/main/gifs/いちゃいちゃ.mp4) | ⬇️ [MP4 (sound)](https://raw.githubusercontent.com/0xzerolight/anki_miner/main/gifs/代.mp4) |

## Tabs

- **Video** - mine a video + subtitle pair, with a screenshot and audio clip on every card.
- **Audio** - mine an audio file + transcript pair.
- **Reading** - mine manga (mokuro), novels (`.epub`, Aozora `.txt`), or standalone subtitle files.
- **Settings** - everything configurable, with a skippable first-run wizard.

## Other Features

- Animated screenshots - the card image can be a short looping clip of the line instead of a still (off by default).
- Extensive filtering: i+1, frequency limits, blacklist, wordsets, proper-noun name lists, and more.
- Offline Yomitan dictionary import - definitions, pitch accent, frequency - chained by priority.
- Word audio from on-device audio packs - the local-audio-yomichan collection or its generated android.db; sentence audio from your device's Japanese text-to-speech.
- Optional Jisho.org online fallback for definitions (slower, rate-limited).
- Interface translated into 11 languages besides English.
- Light and dark themes from ported palettes - Catppuccin, Dracula, Nord, Gruvbox, Solarized, and more. Android 12+ can take colours from your wallpaper.

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

## Recommended Resources

| Type | Resource | Download | Add via |
|------|----------|----------|---------|
| Dictionary | [JMdict](https://github.com/yomidevs/jmdict-yomitan) | [Yomitan zip](https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip) | Onboarding wizard, or Settings -> Dictionaries |
| Dictionary | [Jitendex](https://jitendex.org/) | [Yomitan zip](https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip) | Onboarding wizard, or Settings -> Dictionaries |
| Dictionary | [Bee's Character Dictionary](https://characterdictionary.tokyo/) | Generated on site | Settings -> Dictionaries |
| Pitch | [Kanjium](https://github.com/mifunetoshiro/kanjium) | [TSV](https://raw.githubusercontent.com/mifunetoshiro/kanjium/master/data/source_files/raw/accents.txt) | Settings -> Dictionaries -> Pitch Accent File |
| Pitch | [アクセント辞典v2](https://learnjapanese.moe/yomichan/#dictionaries) | [Drive](https://drive.google.com/drive/folders/1tTdLppnqMfVC5otPlX_cs4ixlIgjv_lH) | Settings -> Dictionaries -> Pitch Accent File |
| Frequency | [JPDB v2.2 Kana](https://github.com/Kuuuube/yomitan-dictionaries) | [Yomitan zip](https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/JPDB_v2.2_Frequency_Kana_2024-10-13.zip) | Settings -> Filtering -> Frequency List File |
| Frequency | [BCCWJ SUW+LUW](https://github.com/Kuuuube/yomitan-dictionaries) | [Yomitan zip](https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/BCCWJ_SUW_LUW_combined.zip) | Settings -> Filtering -> Frequency List File |
| Word audio | [local-audio-yomichan](https://github.com/yomidevs/local-audio-yomichan) | Collection torrent or generated `android.db` | Settings -> Audio |

<details> 
<summary><strong>JMnedict License</strong></summary>
  Proper-noun filtering uses bundled name wordsets derived from [JMnedict](https://www.edrdg.org/enamdict/enamdict_doc.html) (JMdict/EDICT project, EDRDG, CC BY-SA 4.0).
</details>

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Cards not reaching Anki | Install AnkiDroid and grant Anki Miner permission when prompted. |
| APK won't install | Enable install-from-unknown-sources for your browser/file manager; the device must be `arm64-v8a` on Android 8.0+. |
| No definitions found | Add a dictionary in Settings -> Dictionaries, or enable the Jisho fallback (slower, rate-limited). |
| Setup notice on a mining tab | Open Settings and finish the flagged step (tokenizer, dictionary, or AnkiDroid). |
| Sentence audio missing or wrong | Install a Japanese text-to-speech voice in your Android system settings. |
| Word audio missing on cards | Import an audio pack or android.db under Settings -> Audio, and map the expression audio field. |
| Subtitles out of sync | Use the subtitle offset control on the mining screen. |
| Reporting a bug | Settings -> Tester diagnostics can share a redacted log bundle. Check it before attaching it to an issue. |

## Contributing

Contributions of any kind are welcome.

- Build from source and dev setup: [CONTRIBUTING.md](CONTRIBUTING.md).
- Architecture overview: [ARCHITECTURE.md](ARCHITECTURE.md).
- Bug reports and feature requests -> [Issues](https://github.com/0xzerolight/anki_miner_android/issues).
- Security vulnerabilities -> [private advisory](https://github.com/0xzerolight/anki_miner_android/security/advisories/new), not a public issue. See [SECURITY.md](SECURITY.md).

Main project: [Anki Miner](https://github.com/0xzerolight/anki_miner).

## License

GNU General Public License v3.0-or-later. See [LICENSE](LICENSE); bundled third-party components are listed in [NOTICE.md](NOTICE.md).
