package com.ankiminer.android.ui.community

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.ankiminer.android.R
import com.ankiminer.android.ui.links.AppLinks
import com.ankiminer.android.ui.theme.AdaptivePairedActions
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
    val uriHandler = LocalUriHandler.current
    AdaptivePairedActions(
        modifier = modifier,
        first = { buttonModifier ->
            SecondaryActionButton(
                onClick = { uriHandler.openUri(AppLinks.REPOSITORY) },
                modifier = buttonModifier.testTag(CommunityLinksTestTags.STAR),
            ) {
                Text(stringResource(R.string.community_star_project))
            }
        },
        second = { buttonModifier ->
            SecondaryActionButton(
                onClick = { uriHandler.openUri(AppLinks.DISCORD_INVITE) },
                modifier = buttonModifier.testTag(CommunityLinksTestTags.DISCORD),
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
                Text(stringResource(R.string.community_join_discord))
            }
        },
    )
}
