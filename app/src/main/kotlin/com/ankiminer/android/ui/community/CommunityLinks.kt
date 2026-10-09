package com.ankiminer.android.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.ankiminer.android.R
import com.ankiminer.android.ui.links.AppLinks
import com.ankiminer.android.ui.links.rememberExternalLinkOpener
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SecondaryActionButton

internal object CommunityLinksTestTags {
    const val STAR = "community-star"
    const val DISCORD = "community-discord"
}

/**
 * The desktop app's menu-bar pair, in the one place Android has room for it. Peers of each other
 * and of nothing else: they are secondary actions, and they sit below whatever the settings header
 * is already saying, so a setup failure keeps the top of the page.
 */
@Composable
internal fun CommunityLinks(modifier: Modifier = Modifier) {
    val openLink = rememberExternalLinkOpener()
    // One row at every width and text size (owner decision D10): the pair keeps its header place,
    // but stacked it cost two full-width rows on small phones and wrapped to uneven heights.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        SecondaryActionButton(
            onClick = { openLink(AppLinks.REPOSITORY) },
            modifier = Modifier.weight(1f).testTag(CommunityLinksTestTags.STAR),
        ) {
            Text(
                stringResource(R.string.community_star_project),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SecondaryActionButton(
            onClick = { openLink(AppLinks.DISCORD_INVITE) },
            modifier = Modifier.weight(1f).testTag(CommunityLinksTestTags.DISCORD),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_discord),
                // Decorative: the label already names the destination.
                contentDescription = null,
                // Brand blurple in both themes. Tinting it to the button's content colour
                // would turn a recognised mark into a generic glyph.
                tint = Color.Unspecified,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                stringResource(R.string.community_join_discord),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
