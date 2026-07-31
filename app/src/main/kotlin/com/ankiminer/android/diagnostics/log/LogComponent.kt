package com.ankiminer.android.diagnostics.log

/**
 * Subsystem a record came from, written as `c=<wireName>`.
 *
 * The component rides inside the record rather than in the logcat tag so that one `adb logcat -s
 * AnkiMiner` catches every record while `grep 'c=mining'` still works on the exported file. Wire
 * names are lowercase and stable: they are a grep contract, not a display string.
 */
internal enum class LogComponent(val wireName: String) {
    APP("app"),
    BOOTSTRAP("bootstrap"),
    BRIDGE("bridge"),
    MINING("mining"),
    READING("reading"),
    ANKI("anki"),
    JOURNAL("journal"),
    MEDIA("media"),
    SAF("saf"),
    RESOURCES("resources"),
    SERVICE("service"),
    SETTINGS("settings"),
    UI("ui"),
    DIAG("diag"),
}
