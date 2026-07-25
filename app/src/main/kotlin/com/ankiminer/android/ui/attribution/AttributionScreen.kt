package com.ankiminer.android.ui.attribution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceAttribution
import com.ankiminer.android.ui.theme.SectionTitle

@Composable
internal fun AttributionScreen(
    onOpenNotices: () -> Unit,
    modifier: Modifier = Modifier,
    installedDictionaries: List<InstalledDictionary> = emptyList(),
) {
    val catalog = FrozenResourceCatalog.value
    val uriHandler = LocalUriHandler.current
    val occupiedDictionaries = attributionDictionaries(installedDictionaries)
    val installedCatalogAttribution = installedCatalogAttributions(occupiedDictionaries)
    val jitendexInstalled = hasInstalledJitendex(occupiedDictionaries)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "attribution:intro", contentType = "intro") {
            Text(stringResource(R.string.attribution_intro))
        }

        item(key = "attribution:unidic", contentType = "attribution-group") {
            AttributionGroup(stringResource(R.string.attribution_unidic), catalog.unidic.attribution)
        }
        item(key = "license:unidic-lite", contentType = "license-link") {
            LicenseLinkCard(
                title = stringResource(R.string.attribution_unidic_lite_license),
                onOpenNotices = onOpenNotices,
            )
        }
        item(key = "license:unidic", contentType = "license-link") {
            LicenseLinkCard(
                title = stringResource(R.string.attribution_unidic_license),
                onOpenNotices = onOpenNotices,
            )
        }

        item(key = "attribution:icon", contentType = "attribution-card") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardHeading(stringResource(R.string.attribution_icon_title))
                    Text(stringResource(R.string.attribution_icon_text))
                    TextButton(onClick = { uriHandler.openUri(SHIPPORI_URL) }) {
                        Text(SHIPPORI_URL, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item(key = "attribution:dictionaries", contentType = "section-heading") {
            SectionTitle(stringResource(R.string.attribution_installed_dictionaries))
        }
        if (occupiedDictionaries.isEmpty()) {
            item(key = "dictionary:none", contentType = "empty-state") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.attribution_no_installed_dictionaries),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(
                items = occupiedDictionaries,
                key = { dictionary -> "dictionary:${dictionary.slotId}" },
                contentType = { "dictionary" },
            ) { dictionary ->
                InstalledDictionaryAttribution(dictionary)
            }
        }
        if (installedCatalogAttribution.isNotEmpty()) {
            item(key = "attribution:installed-catalog", contentType = "attribution-group") {
                AttributionGroup(
                    stringResource(R.string.attribution_installed_catalog_notices),
                    installedCatalogAttribution,
                )
            }
        }
        if (jitendexInstalled) {
            item(key = "attribution:derived-terms", contentType = "attribution-card") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardHeading(stringResource(R.string.attribution_derived_terms_title))
                        Text(stringResource(R.string.attribution_derived_terms))
                    }
                }
            }
        }

        item(key = "attribution:divider", contentType = "divider") { HorizontalDivider() }
        item(key = "attribution:jisho", contentType = "attribution-card") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardHeading(stringResource(R.string.attribution_jisho_title))
                    Text(stringResource(R.string.attribution_jisho_disclosure))
                    Text(stringResource(R.string.attribution_jisho_rate_limit))
                }
            }
        }

        item(key = "attribution:privacy", contentType = "attribution-card") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardHeading(stringResource(R.string.privacy_title))
                    Text(stringResource(R.string.privacy_local_processing))
                    Text(stringResource(R.string.privacy_network_processing))
                    Text(stringResource(R.string.privacy_retention))
                    TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) {
                        Text(stringResource(R.string.privacy_open_policy))
                    }
                }
            }
        }

        item(key = "attribution:notices", contentType = "attribution-card") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardHeading(stringResource(R.string.source_notices_title))
                    TextButton(onClick = onOpenNotices) {
                        Text(stringResource(R.string.source_open_notices))
                    }
                    TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }) {
                        Text(stringResource(R.string.source_open_repository))
                    }
                }
            }
        }
    }
}

internal fun attributionDictionaries(
    installedDictionaries: List<InstalledDictionary>,
): List<InstalledDictionary> =
    installedDictionaries
        .filter { it.occupied }
        .distinctBy { it.slotId }
        .sortedBy { it.slotId }

/**
 * The derived-terms notice covers Jitendex-bundled upstreams (Tatoeba, Kanji alive,
 * JmdictFurigana) and must never show for other catalog dictionaries.
 */
internal fun hasInstalledJitendex(
    installedDictionaries: List<InstalledDictionary>,
): Boolean {
    val jitendex =
        FrozenResourceCatalog.value.dictionaries.singleOrNull { it.slotId == "jitendex" }
            ?: return false
    return installedDictionaries.any {
        it.occupied && it.slotId == jitendex.slotId && it.catalogResourceId == jitendex.resourceId
    }
}

internal fun installedCatalogAttributions(
    installedDictionaries: List<InstalledDictionary>,
): List<ResourceAttribution> =
    installedDictionaries
        .filter { it.occupied }
        .flatMap { it.attribution }
        .distinctBy { listOf(it.name, it.copyright, it.license, it.url) }

@Composable
private fun InstalledDictionaryAttribution(dictionary: InstalledDictionary) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.attribution_dictionary_title,
                    dictionary.sourceName,
                    dictionary.slotId,
                ),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (dictionary.isUsable) {
                        R.string.attribution_dictionary_valid
                    } else {
                        R.string.attribution_dictionary_invalid
                    },
                ),
            )
            Text(
                stringResource(R.string.attribution_embedded_metadata),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            val labels =
                mapOf(
                    "author" to R.string.attribution_embedded_author,
                    "attribution" to R.string.attribution_embedded_attribution,
                    "description" to R.string.attribution_embedded_description,
                )
            val embedded = labels.mapNotNull { (key, label) ->
                dictionary.embeddedAttribution[key]?.let { label to it }
            }
            if (embedded.isEmpty()) {
                Text(stringResource(R.string.attribution_embedded_absent))
            } else {
                embedded.forEach { (label, value) ->
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(value)
                }
            }
        }
    }
}

@Composable
private fun AttributionGroup(title: String, entries: List<ResourceAttribution>) {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeading(title)
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                Text(
                    entry.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(entry.copyright)
                Text(stringResource(R.string.attribution_license, entry.license))
                TextButton(onClick = { uriHandler.openUri(entry.url) }) {
                    Text(entry.url, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LicenseLinkCard(
    title: String,
    onOpenNotices: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeading(title)
            Text(stringResource(R.string.attribution_license_in_notices))
            TextButton(onClick = onOpenNotices) {
                Text(stringResource(R.string.source_open_notices))
            }
        }
    }
}

@Composable
private fun CardHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

private const val SHIPPORI_URL = "https://fonts.google.com/specimen/Shippori+Mincho+B1"
private const val SOURCE_URL = "https://github.com/0xzerolight/anki_miner_android"
private const val PRIVACY_POLICY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"
