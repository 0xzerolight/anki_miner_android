package com.ankiminer.android.ui.attribution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.ResourceAttribution

@Composable
internal fun AttributionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = FrozenResourceCatalog.value
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.attribution_back)) }
        Text(stringResource(R.string.attribution_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.attribution_intro))

        AttributionGroup(stringResource(R.string.attribution_unidic), catalog.unidic.attribution)
        LicenseText(stringResource(R.string.attribution_unidic_lite_license), MIT_LICENSE)
        LicenseText(stringResource(R.string.attribution_unidic_license), BSD_3_CLAUSE)

        AttributionGroup(stringResource(R.string.attribution_jitendex), catalog.recommendedDictionary.attribution)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.attribution_derived_terms_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.attribution_derived_terms))
            }
        }

        HorizontalDivider()
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.attribution_jisho_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.attribution_jisho_disclosure))
                Text(stringResource(R.string.attribution_jisho_rate_limit))
            }
        }
    }
}

@Composable
private fun AttributionGroup(title: String, entries: List<ResourceAttribution>) {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
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
private fun LicenseText(title: String, license: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(license, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val MIT_LICENSE =
    """Copyright 2020 Paul McCann

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

private const val BSD_3_CLAUSE =
    """Copyright (c) 2011-2017, The UniDic Consortium. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS “AS IS” AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""
