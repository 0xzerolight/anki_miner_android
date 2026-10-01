package com.ankiminer.android.data.resources

/**
 * The committed Python catalog files, as the `resource.catalog` envelope Python sends.
 *
 * The JVM test classpath carries `app/src/main/python/android_bridge`, so every
 * `resource_catalog/<language>.json` is readable here without an emulator.
 */
internal object CommittedCatalogs {
    fun file(language: String): String =
        checkNotNull(javaClass.getResourceAsStream("/resource_catalog/$language.json")) {
            "resource_catalog/$language.json missing from the test classpath"
        }.bufferedReader().use { it.readText().trim() }

    fun payload(): String =
        FrozenResourceCatalog.all.joinToString(prefix = """{"catalogs":[""", postfix = "]}") { file(it.language) }

    fun envelope(): String = """{"schemaVersion":1,"type":"resource.catalog","payload":${payload()}}"""
}
