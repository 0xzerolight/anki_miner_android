package com.ankiminer.android.ui.mining

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** What to pick, and the already chosen document whose folder the picker should open in. */
internal data class DocumentPickRequest(
    val mimeTypes: List<String>,
    val nearDocumentUri: String?,
)

/**
 * OpenDocument that starts in a related document's folder (EXTRA_INITIAL_URI, API 26+). Without it
 * DocumentsUI opens on Recent, which is empty for a file never opened through it.
 */
internal class OpenDocumentNear : ActivityResultContract<DocumentPickRequest, Uri?>() {
    private val openDocument = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: DocumentPickRequest): Intent =
        openDocument.createIntent(context, input.mimeTypes.toTypedArray()).apply {
            input.nearDocumentUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
        }

    override fun getSynchronousResult(
        context: Context,
        input: DocumentPickRequest,
    ): SynchronousResult<Uri?>? = openDocument.getSynchronousResult(context, input.mimeTypes.toTypedArray())

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = openDocument.parseResult(resultCode, intent)
}
