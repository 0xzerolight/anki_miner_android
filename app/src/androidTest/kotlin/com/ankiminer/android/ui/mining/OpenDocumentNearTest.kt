package com.ankiminer.android.ui.mining

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenDocumentNearTest {
    @Test
    fun thePickerOpensBesideTheRelatedDocumentAndKeepsItsTypes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val near = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fepisode.mkv"
        val intent =
            OpenDocumentNear().createIntent(context, DocumentPickRequest(listOf("application/x-subrip", "text/*"), near))
        assertEquals(
            Uri.parse(near),
            IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
        assertEquals(
            listOf("application/x-subrip", "text/*"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList(),
        )
        val plain = OpenDocumentNear().createIntent(context, DocumentPickRequest(listOf("audio/*"), null))
        assertFalse(plain.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }
}
