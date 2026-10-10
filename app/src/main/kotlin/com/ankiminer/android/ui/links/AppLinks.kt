package com.ankiminer.android.ui.links

/**
 * Every outbound project link the app opens. One place, because the repository URL is also the
 * stem of the privacy-policy URL and a divergence between the two is invisible until a user taps.
 */
internal object AppLinks {
    const val REPOSITORY = "https://github.com/0xzerolight/anki_miner_android"
    const val PRIVACY_POLICY = "$REPOSITORY/blob/main/PRIVACY.md"

    /** Same invite the desktop app and both READMEs carry. */
    const val DISCORD_INVITE = "https://discord.com/invite/aDtQyZzUVP"

    /** Anki Miner Note's latest release, which carries its `.apkg` (desktop's setup wizard link). */
    const val ANKI_MINER_NOTE_RELEASES = "https://github.com/0xzerolight/anki_miner_note/releases/latest"
}
