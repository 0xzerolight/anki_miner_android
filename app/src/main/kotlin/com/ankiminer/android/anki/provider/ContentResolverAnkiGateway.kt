package com.ankiminer.android.anki.provider

import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.DeadObjectException
import android.os.Looper
import android.os.OperationCanceledException
import android.os.RemoteException
import com.ankiminer.android.diagnostics.log.LogContext
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.Utils
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun interface ProviderDeadlineScheduler {
    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): CancellationRegistration
}

internal fun interface ProviderResolverQuery {
    fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal,
    ): Cursor?
}

internal fun interface ProviderResolverInsert {
    fun insert(
        uri: Uri,
        values: ContentValues,
    ): Uri?
}

internal fun interface ProviderResolverUpdate {
    fun update(
        uri: Uri,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int
}

internal fun interface ProviderResolverDelete {
    fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int
}

internal object RealProviderDeadlineScheduler : ProviderDeadlineScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(
            1,
            ThreadFactory { runnable ->
                Thread(runnable, "anki-provider-deadline").apply { isDaemon = true }
            },
        ).apply { removeOnCancelPolicy = true }

    override fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): CancellationRegistration {
        val runId = LogContext.runId()
        val future =
            executor.schedule(
                { LogContext.withRunId(runId, action) },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        return CancellationRegistration { future.cancel(false) }
    }
}

internal object AndroidWorkerThreadGuard : WorkerThreadGuard {
    override fun checkWorkerThread() {
        check(Looper.myLooper() !== Looper.getMainLooper()) {
            "AnkiDroid provider callbacks must run off the Android main thread"
        }
    }
}

/** The only production class which translates project-owned operations into Android provider calls. */
internal class ContentResolverAnkiGateway(
    context: Context,
    private val workerThreadGuard: WorkerThreadGuard = AndroidWorkerThreadGuard,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val bulkReadTimeoutMs: Long = DEFAULT_BULK_READ_TIMEOUT_MS,
    private val deadlineScheduler: ProviderDeadlineScheduler = RealProviderDeadlineScheduler,
    private val accessStatusOverride: (() -> ProviderAccessStatus)? = null,
    private val resolverQueryOverride: ProviderResolverQuery? = null,
    private val resolverInsertOverride: ProviderResolverInsert? = null,
    private val resolverUpdateOverride: ProviderResolverUpdate? = null,
    private val resolverDeleteOverride: ProviderResolverDelete? = null,
) : AnkiProviderGateway {
    private val context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver
    private val packageManager: PackageManager = this.context.packageManager

    init {
        require(readTimeoutMs > 0L) { "provider read timeout must be positive" }
        require(bulkReadTimeoutMs >= readTimeoutMs) { "bulk reads must not get the shorter deadline" }
    }

    override fun accessStatus(): ProviderAccessStatus {
        workerThreadGuard.checkWorkerThread()
        accessStatusOverride?.let { return it() }
        val providerInfo = resolveProviderIncludingDisabled()
        if (providerInfo == null) {
            return if (isAnkiDroidInstalled()) {
                ProviderAccessStatus.Incompatible(apiSpecVersion = null)
            } else {
                ProviderAccessStatus.Absent
            }
        }
        if (providerInfo.packageName != ANKIDROID_PACKAGE) {
            return ProviderAccessStatus.Incompatible(apiSpecVersion = null)
        }
        val component = ComponentName(providerInfo.packageName, providerInfo.name)
        val componentState = packageManager.getComponentEnabledSetting(component)
        val componentDisabled =
            componentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                componentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                componentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        if (!providerInfo.enabled || providerInfo.applicationInfo?.enabled == false || componentDisabled) {
            return ProviderAccessStatus.ApiDisabled
        }
        val spec = providerInfo.metaData?.getInt(PROVIDER_SPEC_METADATA, LEGACY_API_SPEC) ?: LEGACY_API_SPEC
        if (spec < MINIMUM_API_SPEC) return ProviderAccessStatus.Incompatible(spec)
        if (
            context.checkSelfPermission(FlashCardsContract.READ_WRITE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ProviderAccessStatus.PermissionRequired
        }
        return ProviderAccessStatus.Available(
            packageName = providerInfo.packageName,
            apiSpecVersion = spec,
            versionCode = installedVersionCode(),
        )
    }

    override fun query(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor? {
        workerThreadGuard.checkWorkerThread()
        requireAvailableAccess()
        if (cancellation.isCancelled()) {
            throw ProviderGatewayException(ProviderFailureKind.CANCELLED)
        }
        if (!ProviderQueryShapes.isAllowed(query)) {
            throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
        }
        val androidQuery = query.toAndroidQuery()
        val queryCancellation =
            ProviderQueryCancellation(
                cancellation = cancellation,
                timeoutMs =
                    when (query.deadline) {
                        ProviderReadDeadline.INTERACTIVE -> readTimeoutMs
                        ProviderReadDeadline.BULK -> bulkReadTimeoutMs
                    },
                scheduler = deadlineScheduler,
            )
        var cursor: Cursor? = null
        var handedOff = false
        try {
            val openedCursor =
                if (resolverQueryOverride != null) {
                    resolverQueryOverride.query(
                        androidQuery.uri,
                        androidQuery.projection,
                        androidQuery.selection,
                        androidQuery.selectionArgs,
                        androidQuery.sortOrder,
                        queryCancellation.signal,
                    )
                } else {
                    resolver.query(
                        androidQuery.uri,
                        androidQuery.projection,
                        androidQuery.selection,
                        androidQuery.selectionArgs,
                        androidQuery.sortOrder,
                        queryCancellation.signal,
                    )
                }
            cursor = openedCursor
            queryCancellation.throwIfCancelled()
            if (openedCursor == null) {
                requireAvailableAccess()
                return null
            }
            val wrapped =
                queryCancellation.checkedCall {
                    AndroidProviderCursor(
                        cursor = openedCursor,
                        projection = query.projection,
                        expectedNames = androidQuery.projection,
                        cancellation = queryCancellation,
                    )
                }
            handedOff = true
            return wrapped
        } catch (error: Exception) {
            throw queryCancellation.mapFailure(error)
        } finally {
            if (!handedOff) {
                if (cursor != null) runCatching { cursor.close() }
                queryCancellation.close()
            }
        }
    }

    override fun fieldChecksum(firstField: String): Long {
        workerThreadGuard.checkWorkerThread()
        return Utils.fieldChecksum(firstField)
    }

    override fun createDeck(command: AnkiProviderMutationCommand.CreateDeck): String? {
        workerThreadGuard.checkWorkerThread()
        requireAvailableAccess()
        val values = ContentValues(1).apply {
            put(FlashCardsContract.Deck.DECK_NAME, command.deckName)
        }
        val returned =
            try {
                if (resolverInsertOverride != null) {
                    resolverInsertOverride.insert(FlashCardsContract.Deck.CONTENT_ALL_URI, values)
                } else {
                    resolver.insert(FlashCardsContract.Deck.CONTENT_ALL_URI, values)
                }
            } catch (error: Exception) {
                throw mapMutationFailure(error)
            }
        return returned?.toString()
    }

    override fun storeMedia(command: AnkiProviderMutationCommand.StoreMedia): String? {
        workerThreadGuard.checkWorkerThread()
        requireAvailableAccess()
        val values =
            ContentValues(2).apply {
                put(FlashCardsContract.AnkiMedia.FILE_URI, command.fileUri)
                put(FlashCardsContract.AnkiMedia.PREFERRED_NAME, command.preferredName)
            }
        val returned = insert(FlashCardsContract.AnkiMedia.CONTENT_URI, values)
        return returned?.toString()
    }

    override fun insertNote(command: AnkiProviderMutationCommand.InsertNote): String? {
        workerThreadGuard.checkWorkerThread()
        requireAvailableAccess()
        val values =
            ContentValues(3).apply {
                put(FlashCardsContract.Note.MID, command.modelId)
                put(FlashCardsContract.Note.FLDS, command.joinedFields)
                put(FlashCardsContract.Note.TAGS, command.providerTagsWire)
            }
        val returned = insert(FlashCardsContract.Note.CONTENT_URI, values)
        return returned?.toString()
    }

    override fun routeCard(command: AnkiProviderMutationCommand.RouteCard): Int {
        workerThreadGuard.checkWorkerThread()
        requireAvailableAccess()
        val noteUri =
            Uri.withAppendedPath(
                FlashCardsContract.Note.CONTENT_URI,
                command.noteId.toString(),
            )
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val cardUri = Uri.withAppendedPath(cardsUri, command.ordinal.toString())
        val values =
            ContentValues(1).apply {
                put(FlashCardsContract.Card.DECK_ID, command.targetDeckId)
            }
        return try {
            if (resolverUpdateOverride != null) {
                resolverUpdateOverride.update(cardUri, values, null, null)
            } else {
                resolver.update(cardUri, values, null, null)
            }
        } catch (error: Exception) {
            throw mapMutationFailure(error)
        }
    }

    override fun deleteNote(command: AnkiProviderMutationCommand.DeleteNote): Int {
        workerThreadGuard.checkWorkerThread()
        requireAvailableAccess()
        val noteUri =
            Uri.withAppendedPath(
                FlashCardsContract.Note.CONTENT_URI,
                command.noteId.toString(),
            )
        return try {
            if (resolverDeleteOverride != null) {
                resolverDeleteOverride.delete(noteUri, null, null)
            } else {
                resolver.delete(noteUri, null, null)
            }
        } catch (error: Exception) {
            throw mapMutationFailure(error)
        }
    }

    private fun insert(
        uri: Uri,
        values: ContentValues,
    ): Uri? =
        try {
            if (resolverInsertOverride != null) {
                resolverInsertOverride.insert(uri, values)
            } else {
                resolver.insert(uri, values)
            }
        } catch (error: Exception) {
            throw mapMutationFailure(error)
        }

    private fun mapMutationFailure(error: Exception): ProviderGatewayException =
        when (error) {
            is ProviderGatewayException -> {
                val normalized = error.kind.normalizedForMutationBoundary()
                if (normalized == error.kind) error else ProviderGatewayException(normalized, error)
            }
            is SecurityException ->
                ProviderGatewayException(ProviderFailureKind.PERMISSION_REQUIRED, error)
            is DeadObjectException, is RemoteException ->
                ProviderGatewayException(ProviderFailureKind.PROVIDER_UNAVAILABLE, error)
            else -> ProviderGatewayException(ProviderFailureKind.MUTATION_FAILED, error)
        }

    private fun requireAvailableAccess() {
        when (accessStatus()) {
            is ProviderAccessStatus.Available -> Unit
            ProviderAccessStatus.PermissionRequired ->
                throw ProviderGatewayException(ProviderFailureKind.PERMISSION_REQUIRED)
            ProviderAccessStatus.Absent ->
                throw ProviderGatewayException(ProviderFailureKind.PROVIDER_UNAVAILABLE)
            ProviderAccessStatus.ApiDisabled, is ProviderAccessStatus.Incompatible ->
                throw ProviderGatewayException(ProviderFailureKind.API_DISABLED)
        }
    }

    private fun resolveProviderIncludingDisabled() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveContentProvider(
                FlashCardsContract.AUTHORITY,
                PackageManager.ComponentInfoFlags.of(PROVIDER_INFO_FLAGS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveContentProvider(FlashCardsContract.AUTHORITY, PROVIDER_INFO_FLAGS)
        }

    private fun isAnkiDroidInstalled(): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    ANKIDROID_PACKAGE,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(
                    ANKIDROID_PACKAGE,
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                )
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun installedVersionCode(): Long? =
        try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        ANKIDROID_PACKAGE,
                        PackageManager.PackageInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private data class AndroidQuery(
        val uri: Uri,
        val projection: Array<String>,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String?,
    )

    private fun ProviderQuery.toAndroidQuery(): AndroidQuery {
        val uri =
            when (endpoint) {
                ProviderEndpoint.NOTES_BROWSER -> FlashCardsContract.Note.CONTENT_URI
                ProviderEndpoint.NOTES_V2 -> FlashCardsContract.Note.CONTENT_URI_V2
                ProviderEndpoint.NOTE_BY_ID -> appendId(FlashCardsContract.Note.CONTENT_URI)
                ProviderEndpoint.MODELS -> FlashCardsContract.Model.CONTENT_URI
                ProviderEndpoint.MODEL_BY_ID -> appendId(FlashCardsContract.Model.CONTENT_URI)
                ProviderEndpoint.MODEL_TEMPLATES ->
                    Uri.withAppendedPath(appendId(FlashCardsContract.Model.CONTENT_URI), "templates")
                ProviderEndpoint.DECKS -> FlashCardsContract.Deck.CONTENT_ALL_URI
                ProviderEndpoint.DECK_BY_ID -> appendId(FlashCardsContract.Deck.CONTENT_ALL_URI)
                ProviderEndpoint.CARDS -> FlashCardsContract.Card.CONTENT_URI
                ProviderEndpoint.CARD_BY_ID -> appendId(FlashCardsContract.Card.CONTENT_URI)
                ProviderEndpoint.CARDS_FOR_NOTE ->
                    Uri.withAppendedPath(
                        appendId(FlashCardsContract.Note.CONTENT_URI),
                        "cards",
                    )
            }
        val compiledSelection =
            compileProviderSelection(
                query = this,
                noteIdColumn = FlashCardsContract.Note._ID,
                modelIdColumn = FlashCardsContract.Note.MID,
                checksumColumn = FlashCardsContract.Note.CSUM,
            )
        return AndroidQuery(
            uri = uri,
            projection = projection.map { column -> column.androidName }.toTypedArray(),
            selection = compiledSelection.text,
            selectionArgs = compiledSelection.arguments?.toTypedArray(),
            sortOrder =
                when (sortOrder) {
                    null -> null
                    ProviderOrder.NOTE_ID_ASCENDING -> "${FlashCardsContract.Note._ID} ASC"
                },
        )
    }

    private fun ProviderQuery.appendId(base: Uri): Uri =
        Uri.withAppendedPath(base, requireNotNull(endpointId).toString())

    private val ProviderColumn.androidName: String
        get() =
            when (this) {
                ProviderColumn.NOTE_ID -> FlashCardsContract.Note._ID
                ProviderColumn.NOTE_MODEL_ID -> FlashCardsContract.Note.MID
                ProviderColumn.NOTE_FIELDS -> FlashCardsContract.Note.FLDS
                ProviderColumn.NOTE_TAGS -> FlashCardsContract.Note.TAGS
                ProviderColumn.NOTE_CHECKSUM -> FlashCardsContract.Note.CSUM
                ProviderColumn.MODEL_ID -> FlashCardsContract.Model._ID
                ProviderColumn.MODEL_NAME -> FlashCardsContract.Model.NAME
                ProviderColumn.MODEL_FIELD_NAMES -> FlashCardsContract.Model.FIELD_NAMES
                ProviderColumn.MODEL_CARD_COUNT -> FlashCardsContract.Model.NUM_CARDS
                ProviderColumn.MODEL_CSS -> FlashCardsContract.Model.CSS
                ProviderColumn.MODEL_DEFAULT_DECK_ID -> FlashCardsContract.Model.DECK_ID
                ProviderColumn.MODEL_SORT_FIELD_INDEX -> FlashCardsContract.Model.SORT_FIELD_INDEX
                ProviderColumn.MODEL_TYPE -> FlashCardsContract.Model.TYPE
                ProviderColumn.MODEL_LATEX_POST -> FlashCardsContract.Model.LATEX_POST
                ProviderColumn.MODEL_LATEX_PRE -> FlashCardsContract.Model.LATEX_PRE
                ProviderColumn.TEMPLATE_MODEL_ID -> FlashCardsContract.CardTemplate.MODEL_ID
                ProviderColumn.TEMPLATE_ORDINAL -> FlashCardsContract.CardTemplate.ORD
                ProviderColumn.TEMPLATE_NAME -> FlashCardsContract.CardTemplate.NAME
                ProviderColumn.TEMPLATE_QUESTION_FORMAT -> FlashCardsContract.CardTemplate.QUESTION_FORMAT
                ProviderColumn.TEMPLATE_ANSWER_FORMAT -> FlashCardsContract.CardTemplate.ANSWER_FORMAT
                ProviderColumn.TEMPLATE_BROWSER_QUESTION_FORMAT -> FlashCardsContract.CardTemplate.BROWSER_QUESTION_FORMAT
                ProviderColumn.TEMPLATE_BROWSER_ANSWER_FORMAT -> FlashCardsContract.CardTemplate.BROWSER_ANSWER_FORMAT
                ProviderColumn.DECK_ID -> FlashCardsContract.Deck.DECK_ID
                ProviderColumn.DECK_NAME -> FlashCardsContract.Deck.DECK_NAME
                ProviderColumn.DECK_DYNAMIC -> FlashCardsContract.Deck.DECK_DYN
                ProviderColumn.CARD_ID -> FlashCardsContract.Card._ID
                ProviderColumn.CARD_NOTE_ID -> FlashCardsContract.Card.NOTE_ID
                ProviderColumn.CARD_ORDINAL -> FlashCardsContract.Card.CARD_ORD
                ProviderColumn.CARD_DECK_ID -> FlashCardsContract.Card.DECK_ID
                ProviderColumn.CARD_ORIGINAL_DECK_ID -> FlashCardsContract.Card.ORIGINAL_DECK_ID
            }

    private inner class AndroidProviderCursor(
        private val cursor: Cursor,
        override val projection: List<ProviderColumn>,
        expectedNames: Array<String>,
        private val cancellation: ProviderQueryCancellation,
    ) : ProviderCursor {
        private var rowAvailable = false
        private var closed = false
        private val indexes: Map<ProviderColumn, Int>

        init {
            if (!cursor.columnNames.contentEquals(expectedNames)) {
                throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
            }
            indexes = projection.mapIndexed { index, column -> column to index }.toMap()
        }

        override fun moveToNext(): Boolean {
            check(!closed)
            rowAvailable = false
            return cancellation.checkedCall(cursor::moveToNext).also { rowAvailable = it }
        }

        override fun cell(column: ProviderColumn): ProviderCell {
            check(!closed && rowAvailable)
            val index = indexes[column] ?: throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
            return cancellation.checkedCall {
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> ProviderCell.Null
                    Cursor.FIELD_TYPE_INTEGER -> ProviderCell.Integer(cursor.getLong(index))
                    Cursor.FIELD_TYPE_STRING -> {
                        val value = cursor.getString(index)
                        providerStringCell(column, value)
                    }
                    else -> throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
                }
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                rowAvailable = false
                try {
                    cursor.close()
                } catch (error: Exception) {
                    throw cancellation.mapFailure(error)
                } finally {
                    cancellation.close()
                }
            }
        }
    }

    private companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        const val PROVIDER_SPEC_METADATA = "com.ichi2.anki.provider.spec"
        const val LEGACY_API_SPEC = 1
        const val MINIMUM_API_SPEC = 2
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L

        // Sized against the walks it covers, not against a screen: the known-vocabulary scan may
        // cross up to a million card rows and a million excluded-deck rows before its own ceilings
        // refuse. Those ceilings are the intended bound; this only stops a hung provider.
        const val DEFAULT_BULK_READ_TIMEOUT_MS = 300_000L
        const val PROVIDER_INFO_FLAGS =
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
    }
}

private enum class ProviderCancellationCause {
    USER,
    TIMEOUT,
}

internal class ProviderQueryCancellation(
    private val cancellation: AnkiCancellation,
    timeoutMs: Long,
    scheduler: ProviderDeadlineScheduler,
) : AutoCloseable {
    val signal = CancellationSignal()
    private val closed = AtomicBoolean(false)
    private val cause = AtomicReference<ProviderCancellationCause?>(null)
    private val userRegistration =
        cancellation.invokeOnCancellation {
            cancel(ProviderCancellationCause.USER)
        }
    private val timeoutRegistration =
        scheduler.schedule(timeoutMs) {
            cancel(ProviderCancellationCause.TIMEOUT)
        }

    fun throwIfCancelled() {
        val currentCause = cause.get()
        if (currentCause != null || cancellation.isCancelled()) {
            throw ProviderGatewayException(
                if (currentCause == ProviderCancellationCause.TIMEOUT) {
                    ProviderFailureKind.TIMEOUT
                } else {
                    ProviderFailureKind.CANCELLED
                },
            )
        }
    }

    fun <T> checkedCall(action: () -> T): T {
        throwIfCancelled()
        val result =
            try {
                action()
            } catch (error: Exception) {
                throw mapFailure(error)
            }
        throwIfCancelled()
        return result
    }

    fun mapFailure(error: Exception): ProviderGatewayException {
        val recordedCause = cause.get()
        val kind = when (recordedCause) {
            ProviderCancellationCause.TIMEOUT -> ProviderFailureKind.TIMEOUT
            ProviderCancellationCause.USER -> ProviderFailureKind.CANCELLED
            null -> {
                if (cancellation.isCancelled()) {
                    return ProviderGatewayException(ProviderFailureKind.CANCELLED, error)
                }
                if (error is ProviderGatewayException) return error
                when (error) {
                    is SecurityException -> ProviderFailureKind.PERMISSION_REQUIRED
                    is OperationCanceledException -> ProviderFailureKind.CANCELLED
                    is DeadObjectException, is RemoteException ->
                        ProviderFailureKind.PROVIDER_UNAVAILABLE
                    else -> ProviderFailureKind.QUERY_FAILED
                }
            }
        }
        return ProviderGatewayException(kind, error)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            timeoutRegistration.close()
            userRegistration.close()
        }
    }

    private fun cancel(newCause: ProviderCancellationCause) {
        if (!closed.get() && cause.compareAndSet(null, newCause)) signal.cancel()
    }
}

internal data class CompiledProviderSelection(
    val text: String?,
    val arguments: List<String>?,
)

internal fun providerStringCell(
    column: ProviderColumn,
    value: String,
): ProviderCell =
    if (column == ProviderColumn.DECK_DYNAMIC && value == "true") {
        ProviderCell.Integer(1L)
    } else if (column == ProviderColumn.DECK_DYNAMIC && value == "false") {
        ProviderCell.Integer(0L)
    } else {
        ProviderCell.Text(value)
    }

internal fun compileProviderSelection(
    query: ProviderQuery,
    noteIdColumn: String,
    modelIdColumn: String,
    checksumColumn: String,
): CompiledProviderSelection {
    require(ProviderQueryShapes.isAllowed(query))
    return when (val selection = query.selection) {
        null -> CompiledProviderSelection(null, null)
        is ProviderSelection.ExcludedDeck ->
            CompiledProviderSelection(excludedDeckSelection(selection.deckName), null)
        is ProviderSelection.CardsForNote ->
            CompiledProviderSelection("nid:${selection.noteId}", null)
        is ProviderSelection.NoteIds -> {
            val placeholders = List(selection.ids.size) { "?" }.joinToString(",")
            CompiledProviderSelection(
                "$noteIdColumn IN ($placeholders)",
                selection.ids.map(Long::toString),
            )
        }
        is ProviderSelection.NoteIdsAfter ->
            CompiledProviderSelection(
                "$noteIdColumn > ?",
                listOf(selection.fromId.toString()),
            )
        is ProviderSelection.DuplicateChecksums -> {
            val placeholders = List(selection.checksums.size) { "?" }.joinToString(",")
            CompiledProviderSelection(
                "$modelIdColumn = ? AND $checksumColumn IN ($placeholders)",
                listOf(selection.modelId.toString()) + selection.checksums.map(Long::toString),
            )
        }
    }
}

private fun excludedDeckSelection(deckName: String): String =
    buildString(deckName.length + 8) {
        append("deck:\"")
        for (character in deckName) {
            if (character == '\\' || character == '"' || character == '*' || character == '_') {
                append('\\')
            }
            append(character)
        }
        append('"')
    }
