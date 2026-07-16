package com.ankiminer.android.anki.journal

import android.database.sqlite.SQLiteDatabase
import com.ankiminer.android.anki.protocol.AnkiRequestDigest
import java.security.MessageDigest

/** Clean pre-release schema. There is intentionally no migration from the discarded JSON scratch schema. */
internal object JournalSchema {
    const val VERSION = 3

    private fun names(values: Iterable<Enum<*>>) = values.joinToString(",") { "'${it.name}'" }

    private val parentStates = names(ParentState.entries)
    private val parentOperations = names(ParentOperation.entries)
    private val phases = names(NoteRoutingPhase.entries)
    private val childOperations = names(ChildOperation.entries)
    private val childStates = names(ChildState.entries)
    private val routingStates = names(RoutingIntentState.entries)
    private val statuses = names(AlignedStatus.entries)
    private val variants = names(TerminalVariant.entries)
    private val errorCodes = names(JournalErrorCode.entries)
    private val leaseStates = names(MediaLeaseState.entries)
    private val reservationStates = names(MediaReservationState.entries)
    private val claimStates = names(MediaClaimState.entries)
    private val purposes = names(MediaPurpose.entries)
    private val mediaKinds = names(MediaKind.entries)
    private val stagingStates = names(StagingState.entries)
    private val remediationKinds = names(RemediationKind.entries)
    private val remediationStates = names(RemediationState.entries)

    internal val requiredTables: Set<String> =
        setOf(
            "parents",
            "parent_request_items",
            "target_expectations",
            "target_expectation_fields",
            "target_expectation_templates",
            "verified_target_decks",
            "active_notes",
            "active_note_fields",
            "active_note_tags",
            "active_note_media_bindings",
            "mutation_children",
            "deck_commands",
            "media_commands",
            "note_commands",
            "card_commands",
            "provider_attempts",
            "deck_receipts",
            "media_receipts",
            "note_receipts",
            "card_receipts",
            "routing_intents",
            "routing_observations",
            "aligned_results",
            "parent_terminal_metadata",
            "media_leases",
            "media_reservations",
            "media_claims",
            "staging_artifacts",
            "remediations",
            "terminal_parent_audit",
            "terminal_result_audit",
            "terminal_target_audit",
            "terminal_outcome_audit",
            "terminal_receipt_audit",
        )

    fun create(db: SQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    /** Lossless additive migration for both durable journal versions shipped before note writes. */
    fun upgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion !in 1..2 || newVersion != VERSION) {
            throw JournalCorruptionException(
                "Unsupported journal schema migration $oldVersion -> $newVersion",
            )
        }

        dropKnownNonTableObjects(db)
        db.execSQL(requireStatement("CREATE TABLE routing_observations"))
        db.execSQL(
            """
            INSERT INTO routing_observations (
                intent_id, parent_id, request_index, card_id, note_id, ordinal, deck_id, observed_at_ms
            )
            SELECT id, parent_id, request_index, card_id, note_id, ordinal, target_deck_id, updated_at_ms
            FROM routing_intents
            WHERE child_id IS NULL AND state = 'VERIFIED' AND pre_update_deck_id = target_deck_id
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO remediations (
                parent_id, claim_id, staging_id, staging_subject_id, kind, state, summary,
                compact_evidence, created_at_ms, updated_at_ms
            )
            SELECT p.id, c.id, NULL, NULL, 'MEDIA_STORED_UNATTACHED', 'OPEN',
                'Stored Anki media was not attached to a verified note',
                'schema=v3;reason=stored-unattached-backfill',
                max(p.updated_at_ms, c.updated_at_ms), max(p.updated_at_ms, c.updated_at_ms)
            FROM parents p JOIN media_claims c
                ON c.run_id = p.run_id AND c.request_id = p.request_id
            WHERE p.operation_kind = 'STORE_MEDIA' AND
                p.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND
                c.state IN ('STORED', 'PRESENT_BYTES_VERIFIED') AND NOT EXISTS(
                    SELECT 1 FROM remediations r
                    WHERE r.claim_id = c.id AND r.kind = 'MEDIA_STORED_UNATTACHED')
            """.trimIndent(),
        )
        nonTableStatements.forEach(db::execSQL)
    }

    private fun requireStatement(prefix: String): String =
        statements.singleOrNull { it.startsWith(prefix) }
            ?: throw IllegalStateException("Missing schema statement: $prefix")

    private val nonTableStatements: List<String> by lazy {
        statements.filter { sql ->
            sql.startsWith("CREATE INDEX") ||
                sql.startsWith("CREATE UNIQUE INDEX") ||
                sql.startsWith("CREATE TRIGGER")
        }
    }

    private fun dropKnownNonTableObjects(db: SQLiteDatabase) {
        val objects =
            db.rawQuery(
                """SELECT type, name FROM sqlite_master
                   WHERE type IN ('index', 'trigger') AND name NOT LIKE 'sqlite_%'
                   ORDER BY type, name""".trimIndent(),
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
        val allowedIndexes = requiredIndexes
        val allowedTriggers = requiredTriggers
        objects.forEach { (type, name) ->
            val allowed = if (type == "index") allowedIndexes else allowedTriggers
            if (name !in allowed || !SAFE_OBJECT_NAME.matches(name)) {
                throw JournalCorruptionException("Unexpected $type during journal migration: $name")
            }
        }
        objects.forEach { (type, name) ->
            db.execSQL("DROP ${type.uppercase()} $name")
        }
    }

    internal val requiredTriggers: Set<String> by lazy { declaredObjectNames("TRIGGER") }
    internal val requiredIndexes: Set<String> by lazy { declaredObjectNames("INDEX") }

    /**
     * A name-only schema check cannot distinguish a real guard from a same-name no-op replacement.
     * Fingerprint the canonical sqlite_master definitions so every table, index, and trigger is part
     * of the recovery contract.
     */
    internal val expectedDefinitionHashes: Map<String, String> by lazy {
        val declaration =
            Regex(
                "^CREATE\\s+(?:UNIQUE\\s+)?(TABLE|INDEX|TRIGGER)\\s+([^\\s(]+)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        buildMap {
            statements.forEach { sql ->
                val match = declaration.find(sql) ?: return@forEach
                val type = match.groupValues[1].lowercase()
                val name = match.groupValues[2]
                val key = "$type:$name"
                check(put(key, definitionHash(sql)) == null) { "Duplicate schema declaration $key" }
            }
        }
    }

    internal fun definitionHash(sql: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonicalDefinition(sql).encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    internal fun canonicalDefinition(sql: String): String {
        val source = sql.trim().removeSuffix(";").trimEnd()
        val canonical = StringBuilder(source.length)
        var quote: Char? = null
        var bracketQuoted = false
        var pendingSpace = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (bracketQuoted) {
                canonical.append(char)
                if (char == ']') bracketQuoted = false
                index += 1
                continue
            }
            val activeQuote = quote
            if (activeQuote != null) {
                canonical.append(char)
                if (char == activeQuote) {
                    if (index + 1 < source.length && source[index + 1] == activeQuote) {
                        canonical.append(source[index + 1])
                        index += 2
                        continue
                    }
                    quote = null
                }
                index += 1
                continue
            }
            when {
                char.isWhitespace() -> pendingSpace = true
                char == '[' -> {
                    if (pendingSpace && canonical.isNotEmpty()) canonical.append(' ')
                    pendingSpace = false
                    bracketQuoted = true
                    canonical.append(char)
                }
                char == '\'' || char == '"' || char == '`' -> {
                    if (pendingSpace && canonical.isNotEmpty()) canonical.append(' ')
                    pendingSpace = false
                    quote = char
                    canonical.append(char)
                }
                else -> {
                    if (pendingSpace && canonical.isNotEmpty()) canonical.append(' ')
                    pendingSpace = false
                    canonical.append(char)
                }
            }
            index += 1
        }
        return canonical.toString()
    }

    private val SAFE_OBJECT_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

    private fun declaredObjectNames(kind: String): Set<String> {
        val pattern = Regex("CREATE\\s+(?:UNIQUE\\s+)?$kind\\s+([^\\s(]+)", RegexOption.IGNORE_CASE)
        return statements.mapNotNull { pattern.find(it)?.groupValues?.get(1) }.toSet()
    }

    private val statements: List<String> by lazy {
        listOf(
            """
            CREATE TABLE parents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL CHECK(length(run_id) > 0),
                request_id TEXT NOT NULL CHECK(length(request_id) > 0),
                operation_kind TEXT NOT NULL CHECK(operation_kind IN ($parentOperations)),
                digest_version INTEGER NOT NULL CHECK(digest_version = ${AnkiRequestDigest.VERSION}),
                request_sha256 TEXT NOT NULL CHECK(length(request_sha256) = 64 AND request_sha256 NOT GLOB '*[^0-9a-f]*'),
                request_item_count INTEGER NOT NULL CHECK(request_item_count > 0),
                state TEXT NOT NULL CHECK(state IN ($parentStates)),
                active_request_index INTEGER CHECK(active_request_index IS NULL OR active_request_index >= 0),
                active_note_id INTEGER CHECK(active_note_id IS NULL OR active_note_id > 0),
                routing_phase TEXT CHECK(routing_phase IS NULL OR routing_phase IN ($phases)),
                has_target_expectation INTEGER NOT NULL DEFAULT 0 CHECK(has_target_expectation IN (0, 1)),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                UNIQUE(run_id, request_id),
                CHECK(operation_kind = 'CREATE_NOTES' OR (active_request_index IS NULL AND active_note_id IS NULL AND routing_phase IS NULL)),
                CHECK(active_request_index IS NOT NULL OR (active_note_id IS NULL AND routing_phase IS NULL)),
                CHECK(routing_phase != 'NOTE_PENDING' OR active_note_id IS NULL),
                CHECK(routing_phase IS NULL OR active_request_index IS NOT NULL)
            )
            """.trimIndent(),
            """
            CREATE TABLE parent_request_items (
                parent_id INTEGER NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
                request_index INTEGER NOT NULL CHECK(request_index >= 0),
                item_id TEXT NOT NULL CHECK(length(item_id) > 0),
                PRIMARY KEY(parent_id, request_index),
                UNIQUE(parent_id, item_id),
                UNIQUE(parent_id, request_index, item_id)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE target_expectations (
                parent_id INTEGER PRIMARY KEY REFERENCES parents(id) ON DELETE CASCADE,
                expected_deck_name TEXT NOT NULL CHECK(length(expected_deck_name) > 0),
                model_id INTEGER NOT NULL CHECK(model_id > 0),
                model_name TEXT NOT NULL CHECK(length(model_name) > 0),
                model_type INTEGER NOT NULL,
                field_count INTEGER NOT NULL CHECK(field_count > 0),
                card_count INTEGER NOT NULL CHECK(card_count > 0),
                sort_field_index INTEGER NOT NULL CHECK(sort_field_index >= 0),
                effective_default_deck_id INTEGER NOT NULL CHECK(effective_default_deck_id > 0),
                css TEXT NOT NULL,
                latex_pre TEXT,
                latex_post TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE target_expectation_fields (
                parent_id INTEGER NOT NULL REFERENCES target_expectations(parent_id) ON DELETE CASCADE,
                field_ordinal INTEGER NOT NULL CHECK(field_ordinal >= 0),
                field_name TEXT NOT NULL CHECK(length(field_name) > 0),
                PRIMARY KEY(parent_id, field_ordinal),
                UNIQUE(parent_id, field_name)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE target_expectation_templates (
                parent_id INTEGER NOT NULL REFERENCES target_expectations(parent_id) ON DELETE CASCADE,
                template_ordinal INTEGER NOT NULL CHECK(template_ordinal >= 0),
                model_id INTEGER NOT NULL CHECK(model_id > 0),
                template_name TEXT NOT NULL CHECK(length(template_name) > 0),
                question_format TEXT NOT NULL,
                answer_format TEXT NOT NULL,
                browser_question_format TEXT,
                browser_answer_format TEXT,
                PRIMARY KEY(parent_id, template_ordinal),
                UNIQUE(parent_id, template_name)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE verified_target_decks (
                parent_id INTEGER PRIMARY KEY REFERENCES target_expectations(parent_id) ON DELETE CASCADE,
                deck_id INTEGER NOT NULL CHECK(deck_id > 0),
                deck_name TEXT NOT NULL CHECK(length(deck_name) > 0),
                deck_dynamic INTEGER NOT NULL CHECK(deck_dynamic = 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE active_notes (
                parent_id INTEGER PRIMARY KEY REFERENCES parents(id) ON DELETE CASCADE,
                request_index INTEGER NOT NULL CHECK(request_index >= 0),
                client_note_id TEXT NOT NULL CHECK(length(client_note_id) > 0),
                item_sha256 TEXT NOT NULL CHECK(length(item_sha256) = 64 AND item_sha256 NOT GLOB '*[^0-9a-f]*'),
                joined_fields TEXT NOT NULL,
                provider_tags_wire TEXT NOT NULL,
                duplicate_key TEXT NOT NULL CHECK(length(duplicate_key) > 0),
                duplicate_first_field TEXT NOT NULL,
                duplicate_occurrence INTEGER NOT NULL CHECK(duplicate_occurrence >= 0),
                is_duplicate INTEGER NOT NULL CHECK(is_duplicate IN (0, 1)),
                field_count INTEGER NOT NULL CHECK(field_count > 0),
                tag_count INTEGER NOT NULL CHECK(tag_count >= 0),
                media_binding_count INTEGER NOT NULL CHECK(media_binding_count >= 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                FOREIGN KEY(parent_id, request_index, client_note_id)
                    REFERENCES parent_request_items(parent_id, request_index, item_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE active_note_fields (
                parent_id INTEGER NOT NULL REFERENCES active_notes(parent_id) ON DELETE CASCADE,
                field_ordinal INTEGER NOT NULL CHECK(field_ordinal >= 0),
                field_name TEXT NOT NULL CHECK(length(field_name) > 0),
                field_value TEXT NOT NULL,
                PRIMARY KEY(parent_id, field_ordinal),
                UNIQUE(parent_id, field_name)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE active_note_tags (
                parent_id INTEGER NOT NULL REFERENCES active_notes(parent_id) ON DELETE CASCADE,
                tag_ordinal INTEGER NOT NULL CHECK(tag_ordinal >= 0),
                tag TEXT NOT NULL CHECK(length(tag) > 0),
                PRIMARY KEY(parent_id, tag_ordinal),
                UNIQUE(parent_id, tag)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE media_leases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL UNIQUE CHECK(length(run_id) > 0),
                capacity INTEGER NOT NULL CHECK(capacity BETWEEN 1 AND $MEDIA_LEASE_CAPACITY),
                state TEXT NOT NULL CHECK(state IN ($leaseStates)),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms)
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX one_active_media_lease ON media_leases((1)) WHERE state = 'ACTIVE'",
            """
            CREATE TABLE media_reservations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lease_id INTEGER NOT NULL REFERENCES media_leases(id) ON DELETE RESTRICT,
                run_id TEXT NOT NULL CHECK(length(run_id) > 0),
                request_id TEXT NOT NULL CHECK(length(request_id) > 0),
                asset_id TEXT NOT NULL CHECK(length(asset_id) > 0),
                requested_filename TEXT NOT NULL CHECK(length(requested_filename) > 0),
                preferred_name TEXT NOT NULL CHECK(length(preferred_name) > 0),
                provider_prefix TEXT NOT NULL CHECK(length(provider_prefix) > 0),
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                purpose TEXT NOT NULL CHECK(purpose IN ($purposes)),
                media_kind TEXT NOT NULL CHECK(media_kind IN ($mediaKinds)),
                state TEXT NOT NULL CHECK(state IN ($reservationStates)),
                claim_id INTEGER UNIQUE REFERENCES media_claims(id) ON DELETE RESTRICT,
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                UNIQUE(run_id, asset_id),
                CHECK((state = 'PROMOTED') = (claim_id IS NOT NULL))
            )
            """.trimIndent(),
            """
            CREATE TABLE media_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL CHECK(length(run_id) > 0),
                request_id TEXT NOT NULL CHECK(length(request_id) > 0),
                asset_id TEXT NOT NULL CHECK(length(asset_id) > 0),
                requested_filename TEXT NOT NULL CHECK(length(requested_filename) > 0),
                preferred_name TEXT NOT NULL CHECK(length(preferred_name) > 0),
                provider_prefix TEXT NOT NULL CHECK(length(provider_prefix) > 0),
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                purpose TEXT NOT NULL CHECK(purpose IN ($purposes)),
                media_kind TEXT NOT NULL CHECK(media_kind IN ($mediaKinds)),
                actual_filename TEXT,
                state TEXT NOT NULL CHECK(state IN ($claimStates)),
                compact_evidence TEXT CHECK(compact_evidence IS NULL OR length(compact_evidence) > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                UNIQUE(run_id, asset_id),
                CHECK(state NOT IN ('STORED', 'PRESENT_BYTES_VERIFIED', 'ATTACHED_VERIFIED') OR actual_filename IS NOT NULL),
                CHECK(state NOT IN ('PENDING', 'COMMIT_UNCERTAIN') OR actual_filename IS NULL)
            )
            """.trimIndent(),
            """
            CREATE TABLE active_note_media_bindings (
                parent_id INTEGER NOT NULL REFERENCES active_notes(parent_id) ON DELETE CASCADE,
                binding_ordinal INTEGER NOT NULL CHECK(binding_ordinal >= 0),
                asset_id TEXT NOT NULL CHECK(length(asset_id) > 0),
                actual_filename TEXT NOT NULL CHECK(length(actual_filename) > 0),
                claim_id INTEGER NOT NULL REFERENCES media_claims(id) ON DELETE RESTRICT,
                PRIMARY KEY(parent_id, binding_ordinal),
                UNIQUE(parent_id, asset_id),
                UNIQUE(parent_id, claim_id)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE mutation_children (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id INTEGER NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
                sequence_number INTEGER NOT NULL CHECK(sequence_number >= 0),
                operation_kind TEXT NOT NULL CHECK(operation_kind IN ($childOperations)),
                identity_key TEXT NOT NULL CHECK(length(identity_key) > 0),
                request_index INTEGER CHECK(request_index IS NULL OR request_index >= 0),
                digest_version INTEGER NOT NULL CHECK(digest_version = ${AnkiRequestDigest.VERSION}),
                request_sha256 TEXT NOT NULL CHECK(length(request_sha256) = 64 AND request_sha256 NOT GLOB '*[^0-9a-f]*'),
                item_sha256 TEXT CHECK(item_sha256 IS NULL OR (length(item_sha256) = 64 AND item_sha256 NOT GLOB '*[^0-9a-f]*')),
                media_claim_id INTEGER REFERENCES media_claims(id) ON DELETE RESTRICT,
                state TEXT NOT NULL CHECK(state IN ($childStates)),
                terminal_evidence TEXT CHECK(terminal_evidence IS NULL OR length(terminal_evidence) > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                UNIQUE(parent_id, sequence_number),
                CHECK((operation_kind = 'DECK_CREATE') = (request_index IS NULL)),
                CHECK((operation_kind = 'MEDIA_INSERT') = (media_claim_id IS NOT NULL)),
                CHECK((operation_kind = 'NOTE_INSERT') = (item_sha256 IS NOT NULL)),
                CHECK((state = 'PREPARED') = (terminal_evidence IS NULL))
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX one_global_prepared_child ON mutation_children((1)) WHERE state = 'PREPARED'",
            "CREATE UNIQUE INDEX unique_child_identity ON mutation_children(parent_id, operation_kind, identity_key)",
            "CREATE INDEX child_parent_index ON mutation_children(parent_id, sequence_number)",
            """
            CREATE TABLE deck_commands (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                deck_name TEXT NOT NULL CHECK(length(deck_name) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE media_commands (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                asset_id TEXT NOT NULL CHECK(length(asset_id) > 0),
                file_uri TEXT NOT NULL CHECK(length(file_uri) > 0),
                preferred_name TEXT NOT NULL CHECK(length(preferred_name) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE note_commands (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                client_note_id TEXT NOT NULL CHECK(length(client_note_id) > 0),
                model_id INTEGER NOT NULL CHECK(model_id > 0),
                joined_fields TEXT NOT NULL,
                provider_tags_wire TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE routing_intents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id INTEGER NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
                request_index INTEGER NOT NULL CHECK(request_index >= 0),
                card_id INTEGER NOT NULL CHECK(card_id > 0),
                note_id INTEGER NOT NULL CHECK(note_id > 0),
                ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                target_deck_id INTEGER NOT NULL CHECK(target_deck_id > 0),
                pre_update_deck_id INTEGER NOT NULL CHECK(pre_update_deck_id > 0),
                child_id INTEGER UNIQUE REFERENCES mutation_children(id) ON DELETE SET NULL,
                state TEXT NOT NULL CHECK(state IN ($routingStates)),
                terminal_evidence TEXT CHECK(terminal_evidence IS NULL OR length(terminal_evidence) > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                UNIQUE(parent_id, card_id),
                UNIQUE(parent_id, request_index, ordinal),
                CHECK(state != 'UPDATE_PREPARED' OR child_id IS NOT NULL),
                CHECK((state IN ('VERIFIED', 'FAILED', 'COMMIT_UNCERTAIN')) = (terminal_evidence IS NOT NULL))
            )
            """.trimIndent(),
            """
            CREATE TABLE routing_observations (
                intent_id INTEGER PRIMARY KEY REFERENCES routing_intents(id) ON DELETE CASCADE,
                parent_id INTEGER NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
                request_index INTEGER NOT NULL CHECK(request_index >= 0),
                card_id INTEGER NOT NULL CHECK(card_id > 0),
                note_id INTEGER NOT NULL CHECK(note_id > 0),
                ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                deck_id INTEGER NOT NULL CHECK(deck_id > 0),
                observed_at_ms INTEGER NOT NULL CHECK(observed_at_ms >= 0),
                UNIQUE(parent_id, card_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE card_commands (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                intent_id INTEGER NOT NULL UNIQUE REFERENCES routing_intents(id) ON DELETE RESTRICT,
                card_id INTEGER NOT NULL CHECK(card_id > 0),
                note_id INTEGER NOT NULL CHECK(note_id > 0),
                ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                target_deck_id INTEGER NOT NULL CHECK(target_deck_id > 0),
                pre_update_deck_id INTEGER NOT NULL CHECK(pre_update_deck_id > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE provider_attempts (
                child_id INTEGER NOT NULL REFERENCES mutation_children(id) ON DELETE CASCADE,
                attempt_number INTEGER NOT NULL CHECK(attempt_number IN (1, 2)),
                recovery_reissue INTEGER NOT NULL CHECK(recovery_reissue IN (0, 1)),
                entered_at_ms INTEGER NOT NULL CHECK(entered_at_ms >= 0),
                PRIMARY KEY(child_id, attempt_number),
                CHECK((attempt_number = 2) = (recovery_reissue = 1))
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE deck_receipts (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                deck_id INTEGER NOT NULL CHECK(deck_id > 0),
                content_uri TEXT NOT NULL CHECK(length(content_uri) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE media_receipts (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                actual_filename TEXT NOT NULL CHECK(length(actual_filename) > 0),
                file_uri TEXT NOT NULL CHECK(length(file_uri) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE note_receipts (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                note_id INTEGER NOT NULL CHECK(note_id > 0),
                content_uri TEXT NOT NULL CHECK(length(content_uri) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE card_receipts (
                child_id INTEGER PRIMARY KEY REFERENCES mutation_children(id) ON DELETE CASCADE,
                affected_count INTEGER NOT NULL CHECK(affected_count = 1)
            )
            """.trimIndent(),
            """
            CREATE TABLE aligned_results (
                parent_id INTEGER NOT NULL,
                request_index INTEGER NOT NULL CHECK(request_index >= 0),
                item_id TEXT NOT NULL CHECK(length(item_id) > 0),
                status_kind TEXT NOT NULL CHECK(status_kind IN ($statuses)),
                committed_id INTEGER CHECK(committed_id IS NULL OR committed_id > 0),
                actual_filename TEXT CHECK(actual_filename IS NULL OR length(actual_filename) > 0),
                error_code TEXT CHECK(error_code IS NULL OR error_code IN ($errorCodes)),
                error_message TEXT CHECK(error_message IS NULL OR length(error_message) > 0),
                error_retryable INTEGER CHECK(error_retryable IS NULL OR error_retryable IN (0, 1)),
                compact_evidence TEXT CHECK(compact_evidence IS NULL OR length(compact_evidence) > 0),
                PRIMARY KEY(parent_id, request_index),
                FOREIGN KEY(parent_id, request_index, item_id)
                    REFERENCES parent_request_items(parent_id, request_index, item_id) ON DELETE CASCADE,
                CHECK((error_code IS NULL AND error_message IS NULL AND error_retryable IS NULL) OR
                      (error_code IS NOT NULL AND error_message IS NOT NULL AND error_retryable IS NOT NULL)),
                CHECK(
                    (status_kind = 'VERIFIED' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL) OR
                    (status_kind = 'STORED' AND committed_id IS NULL AND actual_filename IS NOT NULL AND error_code IS NULL) OR
                    (status_kind = 'FAILED' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NOT NULL) OR
                    (status_kind = 'UNCERTAIN' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL) OR
                    (status_kind = 'NOT_ATTEMPTED' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL AND compact_evidence IS NULL) OR
                    (status_kind = 'CREATED' AND committed_id IS NOT NULL AND actual_filename IS NULL AND error_code IS NULL) OR
                    (status_kind = 'DUPLICATE' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL AND compact_evidence IS NULL) OR
                    (status_kind = 'COMMITTED_FAILED' AND committed_id IS NOT NULL AND actual_filename IS NULL AND error_code IS NOT NULL)
                )
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE UNIQUE INDEX unique_stored_filename_per_parent ON aligned_results(parent_id, actual_filename) WHERE status_kind = 'STORED'",
            "CREATE UNIQUE INDEX unique_committed_note_per_parent ON aligned_results(parent_id, committed_id) WHERE status_kind IN ('CREATED', 'COMMITTED_FAILED')",
            """
            CREATE TABLE parent_terminal_metadata (
                parent_id INTEGER PRIMARY KEY REFERENCES parents(id) ON DELETE CASCADE,
                variant_kind TEXT NOT NULL CHECK(variant_kind IN ($variants)),
                error_code TEXT CHECK(error_code IS NULL OR error_code IN ($errorCodes)),
                error_message TEXT CHECK(error_message IS NULL OR length(error_message) > 0),
                error_retryable INTEGER CHECK(error_retryable IS NULL OR error_retryable IN (0, 1)),
                CHECK((error_code IS NULL AND error_message IS NULL AND error_retryable IS NULL) OR
                      (error_code IS NOT NULL AND error_message IS NOT NULL AND error_retryable IS NOT NULL))
            )
            """.trimIndent(),
            """
            CREATE TABLE staging_artifacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL CHECK(length(run_id) > 0),
                request_id TEXT NOT NULL CHECK(length(request_id) > 0),
                asset_id TEXT NOT NULL CHECK(length(asset_id) > 0),
                relative_path TEXT NOT NULL CHECK(length(relative_path) > 0 AND substr(relative_path, 1, 1) != '/'),
                content_uri TEXT NOT NULL CHECK(length(content_uri) > 0),
                package_name TEXT NOT NULL CHECK(length(package_name) > 0),
                size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                state TEXT NOT NULL CHECK(state IN ($stagingStates)),
                compact_evidence TEXT CHECK(compact_evidence IS NULL OR length(compact_evidence) > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                UNIQUE(run_id, asset_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE remediations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id INTEGER REFERENCES parents(id) ON DELETE SET NULL,
                claim_id INTEGER REFERENCES media_claims(id) ON DELETE SET NULL,
                staging_id INTEGER REFERENCES staging_artifacts(id) ON DELETE SET NULL,
                staging_subject_id INTEGER CHECK(staging_subject_id IS NULL OR staging_subject_id > 0),
                kind TEXT NOT NULL CHECK(kind IN ($remediationKinds)),
                state TEXT NOT NULL CHECK(state IN ($remediationStates)),
                summary TEXT NOT NULL CHECK(length(summary) > 0),
                compact_evidence TEXT CHECK(compact_evidence IS NULL OR length(compact_evidence) > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= created_at_ms),
                CHECK(parent_id IS NOT NULL OR claim_id IS NOT NULL OR staging_subject_id IS NOT NULL),
                CHECK(staging_id IS NULL OR staging_id = staging_subject_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE terminal_parent_audit (
                parent_id INTEGER PRIMARY KEY REFERENCES parents(id) ON DELETE RESTRICT,
                final_state TEXT NOT NULL CHECK(final_state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED')),
                terminal_variant TEXT CHECK(terminal_variant IS NULL OR terminal_variant IN ($variants)),
                result_count INTEGER NOT NULL CHECK(result_count >= 0),
                child_count INTEGER NOT NULL CHECK(child_count >= 0),
                finalized_at_ms INTEGER NOT NULL CHECK(finalized_at_ms >= 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE terminal_result_audit (
                parent_id INTEGER NOT NULL REFERENCES terminal_parent_audit(parent_id) ON DELETE RESTRICT,
                request_index INTEGER NOT NULL CHECK(request_index >= 0),
                item_id TEXT NOT NULL CHECK(length(item_id) > 0),
                status_kind TEXT NOT NULL CHECK(status_kind IN ($statuses)),
                committed_id INTEGER CHECK(committed_id IS NULL OR committed_id > 0),
                actual_filename TEXT CHECK(actual_filename IS NULL OR length(actual_filename) > 0),
                error_code TEXT CHECK(error_code IS NULL OR error_code IN ($errorCodes)),
                error_retryable INTEGER CHECK(error_retryable IS NULL OR error_retryable IN (0, 1)),
                compact_evidence TEXT CHECK(compact_evidence IS NULL OR length(compact_evidence) > 0),
                PRIMARY KEY(parent_id, request_index),
                CHECK((error_code IS NULL AND error_retryable IS NULL) OR
                      (error_code IS NOT NULL AND error_retryable IS NOT NULL)),
                CHECK(
                    (status_kind = 'VERIFIED' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL) OR
                    (status_kind = 'STORED' AND committed_id IS NULL AND actual_filename IS NOT NULL AND error_code IS NULL) OR
                    (status_kind = 'FAILED' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NOT NULL) OR
                    (status_kind = 'UNCERTAIN' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL) OR
                    (status_kind = 'NOT_ATTEMPTED' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL AND compact_evidence IS NULL) OR
                    (status_kind = 'CREATED' AND committed_id IS NOT NULL AND actual_filename IS NULL AND error_code IS NULL) OR
                    (status_kind = 'DUPLICATE' AND committed_id IS NULL AND actual_filename IS NULL AND error_code IS NULL AND compact_evidence IS NULL) OR
                    (status_kind = 'COMMITTED_FAILED' AND committed_id IS NOT NULL AND actual_filename IS NULL AND error_code IS NOT NULL)
                )
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE terminal_target_audit (
                parent_id INTEGER PRIMARY KEY REFERENCES terminal_parent_audit(parent_id) ON DELETE RESTRICT,
                status_kind TEXT NOT NULL CHECK(status_kind IN ('VERIFIED', 'FAILED')),
                expected_deck_name TEXT CHECK(expected_deck_name IS NULL OR length(expected_deck_name) > 0),
                model_id INTEGER CHECK(model_id IS NULL OR model_id > 0),
                model_name TEXT CHECK(model_name IS NULL OR length(model_name) > 0),
                verified_deck_id INTEGER CHECK(verified_deck_id IS NULL OR verified_deck_id > 0),
                verified_deck_name TEXT CHECK(verified_deck_name IS NULL OR length(verified_deck_name) > 0),
                returned_deck_id INTEGER CHECK(returned_deck_id IS NULL OR returned_deck_id > 0),
                CHECK((expected_deck_name IS NULL AND model_id IS NULL AND model_name IS NULL) OR
                      (expected_deck_name IS NOT NULL AND model_id IS NOT NULL AND model_name IS NOT NULL)),
                CHECK((verified_deck_id IS NULL AND verified_deck_name IS NULL) OR
                      (verified_deck_id IS NOT NULL AND verified_deck_name = expected_deck_name)),
                CHECK(status_kind != 'VERIFIED' OR verified_deck_id IS NOT NULL),
                CHECK(returned_deck_id IS NULL OR expected_deck_name IS NOT NULL)
            )
            """.trimIndent(),
            """
            CREATE TABLE terminal_outcome_audit (
                parent_id INTEGER NOT NULL REFERENCES terminal_parent_audit(parent_id) ON DELETE RESTRICT,
                status_kind TEXT NOT NULL CHECK(status_kind IN ($statuses)),
                row_count INTEGER NOT NULL CHECK(row_count > 0),
                PRIMARY KEY(parent_id, status_kind)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE terminal_receipt_audit (
                parent_id INTEGER NOT NULL REFERENCES terminal_parent_audit(parent_id) ON DELETE RESTRICT,
                operation_kind TEXT NOT NULL CHECK(operation_kind IN ($childOperations)),
                receipt_count INTEGER NOT NULL CHECK(receipt_count > 0),
                PRIMARY KEY(parent_id, operation_kind)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX unfinished_parent_index ON parents(state, created_at_ms, id)",
            "CREATE INDEX unresolved_claim_index ON media_claims(state, id)",
            "CREATE INDEX claim_namespace_direct_index ON media_claims(COALESCE(actual_filename, requested_filename)) WHERE state != 'CLEANED_VERIFIED'",
            "CREATE INDEX claim_namespace_prefix_index ON media_claims(provider_prefix) WHERE state != 'CLEANED_VERIFIED'",
            "CREATE INDEX staging_recovery_index ON staging_artifacts(state, id)",
            "CREATE INDEX remediation_open_index ON remediations(state, id)",
            "CREATE UNIQUE INDEX one_stored_unattached_remediation_per_claim ON remediations(claim_id) WHERE kind = 'MEDIA_STORED_UNATTACHED'",
            parentIdentityTrigger,
            *parentNormalizedTransitionTriggers.toTypedArray(),
            parentTransitionTrigger,
            parentFinalizationTrigger,
            requestItemInsertTrigger,
            immutableRequestItemTrigger,
            targetInsertTrigger,
            immutableTargetTrigger,
            *targetChildTriggers.toTypedArray(),
            activeNoteInsertTrigger,
            *activeNoteChildTriggers.toTypedArray(),
            childInsertTrigger,
            childIdentityTrigger,
            childTransitionTrigger,
            *commandTriggers.toTypedArray(),
            attemptInsertTrigger,
            immutableAttemptTrigger,
            *receiptTriggers.toTypedArray(),
            resultInsertTrigger,
            immutableResultTrigger,
            *immutablePayloadTriggers.toTypedArray(),
            *durableLedgerTriggers.toTypedArray(),
            terminalMetadataTrigger,
            resultReadyTrigger,
            phaseTransitionTrigger,
            routingInsertTrigger,
            routingObservationInsertTrigger,
            routingObservationImmutableTrigger,
            routingTransitionTrigger,
            claimTransitionTrigger,
            storedUnattachedClaimGuard,
            storedUnattachedClaimResolutionTrigger,
            stagingTransitionTrigger,
        )
    }

    private val parentIdentityTrigger =
        """
        CREATE TRIGGER parent_identity_immutable BEFORE UPDATE ON parents
        WHEN NEW.run_id != OLD.run_id OR NEW.request_id != OLD.request_id OR
             NEW.operation_kind != OLD.operation_kind OR NEW.digest_version != OLD.digest_version OR
             NEW.request_sha256 != OLD.request_sha256 OR NEW.request_item_count != OLD.request_item_count OR
             NEW.created_at_ms != OLD.created_at_ms
        BEGIN SELECT RAISE(ABORT, 'parent identity is immutable'); END
        """.trimIndent()

    private val parentNormalizedTransitionTriggers =
        listOf(
            """
            CREATE TRIGGER parent_target_flag_guard BEFORE UPDATE OF has_target_expectation ON parents
            BEGIN
                SELECT CASE WHEN NOT (
                    NEW.has_target_expectation = OLD.has_target_expectation OR
                    (OLD.has_target_expectation = 0 AND NEW.has_target_expectation = 1 AND
                        NEW.state IN ('PREPARED', 'RUNNING') AND EXISTS(
                            SELECT 1 FROM target_expectations t WHERE t.parent_id = OLD.id AND
                                t.field_count = (SELECT count(*) FROM target_expectation_fields f WHERE f.parent_id = OLD.id) AND
                                t.card_count = (SELECT count(*) FROM target_expectation_templates x WHERE x.parent_id = OLD.id))) OR
                    (OLD.has_target_expectation = 1 AND NEW.has_target_expectation = 0 AND
                        NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED')))
                    THEN RAISE(ABORT, 'target expectation flag differs from exact normalized snapshot') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER parent_active_note_assignment_guard BEFORE UPDATE OF active_request_index ON parents
            BEGIN
                SELECT CASE WHEN NOT (
                    NEW.active_request_index IS OLD.active_request_index OR
                    (OLD.active_request_index IS NULL AND NEW.active_request_index IS NOT NULL AND
                        NEW.active_note_id IS NULL AND NEW.routing_phase = 'NOTE_PENDING' AND EXISTS(
                            SELECT 1 FROM active_notes n WHERE n.parent_id = OLD.id AND
                                n.request_index = NEW.active_request_index AND
                                n.field_count = (SELECT count(*) FROM active_note_fields f WHERE f.parent_id = OLD.id) AND
                                n.tag_count = (SELECT count(*) FROM active_note_tags t WHERE t.parent_id = OLD.id) AND
                                n.media_binding_count =
                                    (SELECT count(*) FROM active_note_media_bindings b WHERE b.parent_id = OLD.id))) OR
                    (OLD.active_request_index IS NOT NULL AND NEW.active_request_index IS NULL AND (
                        NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') OR
                        EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND
                            r.request_index = OLD.active_request_index AND (
                                (r.status_kind = 'CREATED' AND OLD.routing_phase = 'POSTCHECK_VERIFIED' AND
                                    r.committed_id = OLD.active_note_id) OR
                                (r.status_kind IN ('FAILED', 'UNCERTAIN') AND
                                    OLD.routing_phase = 'NOTE_PENDING' AND OLD.active_note_id IS NULL) OR
                                (r.status_kind = 'COMMITTED_FAILED' AND OLD.active_note_id = r.committed_id AND
                                    OLD.routing_phase IN ('NOTE_COMMIT_KNOWN', 'NOTE_READBACK_VERIFIED',
                                        'CARDS_DISCOVERED', 'ROUTING', 'ROUTED', 'POSTCHECK_VERIFIED'))))))
                    THEN RAISE(ABORT, 'active-note scalar differs from normalized materialization') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER parent_active_note_id_guard BEFORE UPDATE OF active_note_id ON parents
            BEGIN
                SELECT CASE WHEN NOT (
                    NEW.active_note_id IS OLD.active_note_id OR
                    (OLD.active_note_id IS NULL AND NEW.active_note_id IS NOT NULL AND
                        OLD.routing_phase = 'NOTE_PENDING' AND NEW.routing_phase = 'NOTE_COMMIT_KNOWN' AND EXISTS(
                            SELECT 1 FROM mutation_children c JOIN note_receipts r ON r.child_id = c.id
                            WHERE c.parent_id = OLD.id AND c.request_index = OLD.active_request_index AND
                                c.state = 'PREPARED' AND r.note_id = NEW.active_note_id)) OR
                    (OLD.active_note_id IS NOT NULL AND NEW.active_note_id IS NULL AND
                        NEW.active_request_index IS NULL AND (
                            NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') OR
                            EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND
                                r.request_index = OLD.active_request_index AND r.committed_id = OLD.active_note_id AND (
                                    (r.status_kind = 'CREATED' AND OLD.routing_phase = 'POSTCHECK_VERIFIED') OR
                                    (r.status_kind = 'COMMITTED_FAILED' AND OLD.routing_phase IN (
                                        'NOTE_COMMIT_KNOWN', 'NOTE_READBACK_VERIFIED', 'CARDS_DISCOVERED',
                                        'ROUTING', 'ROUTED', 'POSTCHECK_VERIFIED'))))))
                    THEN RAISE(ABORT, 'active note ID lacks exact receipt or completion proof') END;
            END
            """.trimIndent(),
        )

    private val parentTransitionTrigger =
        """
        CREATE TRIGGER parent_state_transition BEFORE UPDATE OF state ON parents
        WHEN NOT (
            (OLD.state = 'PREPARED' AND NEW.state IN ('RUNNING', 'RESULT_READY')) OR
            (OLD.state = 'RUNNING' AND NEW.state = 'RESULT_READY') OR
            (OLD.state = 'RESULT_READY' AND NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED'))
        )
        BEGIN
            SELECT RAISE(ABORT, 'illegal parent state transition');
        END
        """.trimIndent()

    private val parentFinalizationTrigger =
        """
        CREATE TRIGGER parent_finalization_guard BEFORE UPDATE OF state ON parents
        WHEN NEW.state IN ('RESULT_READY', 'RESPONSE_ACKNOWLEDGED', 'ABANDONED')
        BEGIN
            SELECT CASE WHEN EXISTS(SELECT 1 FROM mutation_children WHERE parent_id = OLD.id AND state = 'PREPARED')
                THEN RAISE(ABORT, 'finalized parent retains PREPARED child') END;
            SELECT CASE WHEN NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND
                OLD.operation_kind = 'STORE_MEDIA' AND EXISTS(
                    SELECT 1 FROM media_claims c
                    WHERE c.run_id = OLD.run_id AND c.request_id = OLD.request_id AND
                        c.state IN ('STORED', 'PRESENT_BYTES_VERIFIED') AND NOT EXISTS(
                            SELECT 1 FROM remediations r
                            WHERE r.parent_id = OLD.id AND r.claim_id = c.id AND
                                r.kind = 'MEDIA_STORED_UNATTACHED' AND r.state = 'OPEN'))
                THEN RAISE(ABORT, 'final stored media lacks unattached remediation') END;
            SELECT CASE WHEN NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND NOT EXISTS(
                SELECT 1 FROM terminal_parent_audit a WHERE a.parent_id = OLD.id AND
                    a.final_state = NEW.state AND a.terminal_variant IS
                        (SELECT variant_kind FROM parent_terminal_metadata WHERE parent_id = OLD.id) AND
                    a.result_count = (SELECT count(*) FROM aligned_results WHERE parent_id = OLD.id) AND
                    a.child_count = (SELECT count(*) FROM mutation_children WHERE parent_id = OLD.id))
                THEN RAISE(ABORT, 'final parent lacks exact compact parent audit') END;
            SELECT CASE WHEN NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND (
                (SELECT count(*) FROM terminal_result_audit WHERE parent_id = OLD.id) !=
                    (SELECT count(*) FROM aligned_results WHERE parent_id = OLD.id) OR
                EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND NOT EXISTS(
                    SELECT 1 FROM terminal_result_audit a WHERE a.parent_id = r.parent_id AND
                        a.request_index = r.request_index AND a.item_id = r.item_id AND
                        a.status_kind = r.status_kind AND a.committed_id IS r.committed_id AND
                        a.actual_filename IS r.actual_filename AND a.error_code IS r.error_code AND
                        a.error_retryable IS r.error_retryable AND a.compact_evidence IS r.compact_evidence)))
                THEN RAISE(ABORT, 'final parent lacks exact compact per-result audit') END;
            SELECT CASE WHEN NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND (
                (OLD.operation_kind = 'VERIFY_TARGET' AND (
                    (SELECT count(*) FROM terminal_target_audit WHERE parent_id = OLD.id) != 1 OR NOT EXISTS(
                        SELECT 1 FROM terminal_target_audit t JOIN terminal_result_audit r
                            ON r.parent_id = t.parent_id AND r.request_index = 0
                        WHERE t.parent_id = OLD.id AND t.status_kind = r.status_kind))) OR
                (OLD.operation_kind != 'VERIFY_TARGET' AND
                    EXISTS(SELECT 1 FROM terminal_target_audit WHERE parent_id = OLD.id)))
                THEN RAISE(ABORT, 'final parent lacks exact compact target audit') END;
            SELECT CASE WHEN NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND EXISTS(
                SELECT status_kind, count(*) FROM aligned_results WHERE parent_id = OLD.id GROUP BY status_kind
                EXCEPT
                SELECT status_kind, row_count FROM terminal_outcome_audit WHERE parent_id = OLD.id)
                THEN RAISE(ABORT, 'final parent lacks exact compact outcome audit') END;
            SELECT CASE WHEN NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND EXISTS(
                SELECT c.operation_kind, count(*) FROM mutation_children c WHERE c.parent_id = OLD.id AND (
                    EXISTS(SELECT 1 FROM deck_receipts r WHERE r.child_id = c.id) OR
                    EXISTS(SELECT 1 FROM media_receipts r WHERE r.child_id = c.id) OR
                    EXISTS(SELECT 1 FROM note_receipts r WHERE r.child_id = c.id) OR
                    EXISTS(SELECT 1 FROM card_receipts r WHERE r.child_id = c.id)) GROUP BY c.operation_kind
                EXCEPT
                SELECT operation_kind, receipt_count FROM terminal_receipt_audit WHERE parent_id = OLD.id)
                THEN RAISE(ABORT, 'final parent lacks exact compact receipt audit') END;
        END
        """.trimIndent()

    private val requestItemInsertTrigger =
        """
        CREATE TRIGGER request_item_contiguous BEFORE INSERT ON parent_request_items
        BEGIN
            SELECT CASE WHEN NEW.request_index != (SELECT count(*) FROM parent_request_items WHERE parent_id = NEW.parent_id)
                THEN RAISE(ABORT, 'request items must be contiguous') END;
            SELECT CASE WHEN NEW.request_index >= (SELECT request_item_count FROM parents WHERE id = NEW.parent_id)
                THEN RAISE(ABORT, 'request item exceeds frozen parent count') END;
            SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) != 'PREPARED'
                THEN RAISE(ABORT, 'request items must be frozen during parent creation') END;
        END
        """.trimIndent()

    private val immutableRequestItemTrigger =
        """
        CREATE TRIGGER request_item_immutable BEFORE UPDATE ON parent_request_items
        BEGIN SELECT RAISE(ABORT, 'request item is immutable'); END
        """.trimIndent()

    private val targetInsertTrigger =
        """
        CREATE TRIGGER target_expectation_insert_guard BEFORE INSERT ON target_expectations
        BEGIN
            SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                THEN RAISE(ABORT, 'target expectation parent is not mutable') END;
        END
        """.trimIndent()

    private val immutableTargetTrigger =
        """
        CREATE TRIGGER target_expectation_immutable BEFORE UPDATE ON target_expectations
        BEGIN SELECT RAISE(ABORT, 'target expectation is immutable'); END
        """.trimIndent()

    private val targetChildTriggers =
        listOf(
            """
            CREATE TRIGGER target_field_insert_guard BEFORE INSERT ON target_expectation_fields
            BEGIN
                SELECT CASE WHEN NEW.field_ordinal !=
                    (SELECT count(*) FROM target_expectation_fields WHERE parent_id = NEW.parent_id) OR
                    NEW.field_ordinal >= (SELECT field_count FROM target_expectations WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'target fields must exactly fill the frozen count') END;
                SELECT CASE WHEN (SELECT has_target_expectation FROM parents WHERE id = NEW.parent_id) != 0
                    THEN RAISE(ABORT, 'target field construction is already frozen') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER target_template_insert_guard BEFORE INSERT ON target_expectation_templates
            BEGIN
                SELECT CASE WHEN NEW.template_ordinal !=
                    (SELECT count(*) FROM target_expectation_templates WHERE parent_id = NEW.parent_id) OR
                    NEW.template_ordinal >= (SELECT card_count FROM target_expectations WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'target templates must exactly fill the frozen count') END;
                SELECT CASE WHEN (SELECT has_target_expectation FROM parents WHERE id = NEW.parent_id) != 0
                    THEN RAISE(ABORT, 'target template construction is already frozen') END;
                SELECT CASE WHEN NEW.model_id !=
                    (SELECT model_id FROM target_expectations WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'target template model differs') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER verified_target_deck_guard BEFORE INSERT ON verified_target_decks
            BEGIN
                SELECT CASE WHEN NEW.deck_name !=
                    (SELECT expected_deck_name FROM target_expectations WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'verified deck name differs from expectation') END;
                SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                    THEN RAISE(ABORT, 'verified target parent is not mutable') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER verified_target_deck_immutable BEFORE UPDATE ON verified_target_decks
            BEGIN SELECT RAISE(ABORT, 'verified target deck is immutable'); END
            """.trimIndent(),
        )

    private val activeNoteInsertTrigger =
        """
        CREATE TRIGGER active_note_insert_guard BEFORE INSERT ON active_notes
        BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) != 'CREATE_NOTES'
                THEN RAISE(ABORT, 'active note requires createNotes parent') END;
            SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                THEN RAISE(ABORT, 'active note parent is not mutable') END;
            SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM verified_target_decks WHERE parent_id = NEW.parent_id)
                THEN RAISE(ABORT, 'active note lacks verified target') END;
        END
        """.trimIndent()

    private val activeNoteChildTriggers =
        listOf(
            """
            CREATE TRIGGER active_note_field_insert_guard BEFORE INSERT ON active_note_fields
            BEGIN
                SELECT CASE WHEN NEW.field_ordinal !=
                    (SELECT count(*) FROM active_note_fields WHERE parent_id = NEW.parent_id) OR
                    NEW.field_ordinal >= (SELECT field_count FROM active_notes WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'active-note fields must exactly fill the frozen count') END;
                SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                    THEN RAISE(ABORT, 'active-note field parent is not mutable') END;
                SELECT CASE WHEN (SELECT active_request_index FROM parents WHERE id = NEW.parent_id) IS NOT NULL
                    THEN RAISE(ABORT, 'active-note field construction is already frozen') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER active_note_tag_insert_guard BEFORE INSERT ON active_note_tags
            BEGIN
                SELECT CASE WHEN NEW.tag_ordinal !=
                    (SELECT count(*) FROM active_note_tags WHERE parent_id = NEW.parent_id) OR
                    NEW.tag_ordinal >= (SELECT tag_count FROM active_notes WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'active-note tags must exactly fill the frozen count') END;
                SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                    THEN RAISE(ABORT, 'active-note tag parent is not mutable') END;
                SELECT CASE WHEN (SELECT active_request_index FROM parents WHERE id = NEW.parent_id) IS NOT NULL
                    THEN RAISE(ABORT, 'active-note tag construction is already frozen') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER active_note_media_binding_insert_guard BEFORE INSERT ON active_note_media_bindings
            BEGIN
                SELECT CASE WHEN NEW.binding_ordinal !=
                    (SELECT count(*) FROM active_note_media_bindings WHERE parent_id = NEW.parent_id) OR
                    NEW.binding_ordinal >= (SELECT media_binding_count FROM active_notes WHERE parent_id = NEW.parent_id)
                    THEN RAISE(ABORT, 'active-note media bindings must exactly fill the frozen count') END;
                SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                    THEN RAISE(ABORT, 'active-note media-binding parent is not mutable') END;
                SELECT CASE WHEN (SELECT active_request_index FROM parents WHERE id = NEW.parent_id) IS NOT NULL
                    THEN RAISE(ABORT, 'active-note media-binding construction is already frozen') END;
                SELECT CASE WHEN NOT EXISTS(
                    SELECT 1 FROM media_claims c JOIN parents p ON p.id = NEW.parent_id
                    WHERE c.id = NEW.claim_id AND c.run_id = p.run_id AND c.asset_id = NEW.asset_id AND
                          c.actual_filename = NEW.actual_filename AND
                          c.state IN ('STORED', 'PRESENT_BYTES_VERIFIED', 'ATTACHED_VERIFIED'))
                    THEN RAISE(ABORT, 'active-note media binding differs from durable claim') END;
            END
            """.trimIndent(),
        )

    private val childInsertTrigger =
        """
        CREATE TRIGGER child_insert_guard BEFORE INSERT ON mutation_children
        BEGIN
            SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                THEN RAISE(ABORT, 'child parent is not mutable') END;
            SELECT CASE WHEN NEW.sequence_number != (SELECT count(*) FROM mutation_children WHERE parent_id = NEW.parent_id)
                THEN RAISE(ABORT, 'child sequence must be contiguous') END;
            SELECT CASE WHEN NEW.digest_version != (SELECT digest_version FROM parents WHERE id = NEW.parent_id) OR
                                  NEW.request_sha256 != (SELECT request_sha256 FROM parents WHERE id = NEW.parent_id)
                THEN RAISE(ABORT, 'child digest does not match parent') END;
            SELECT CASE WHEN
                (NEW.operation_kind = 'DECK_CREATE' AND (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) != 'VERIFY_TARGET') OR
                (NEW.operation_kind = 'MEDIA_INSERT' AND (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) != 'STORE_MEDIA') OR
                (NEW.operation_kind IN ('NOTE_INSERT', 'CARD_DECK_UPDATE') AND (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) != 'CREATE_NOTES')
                THEN RAISE(ABORT, 'child operation does not match parent') END;
            SELECT CASE WHEN NEW.request_index IS NOT NULL AND
                NOT EXISTS(SELECT 1 FROM parent_request_items WHERE parent_id = NEW.parent_id AND request_index = NEW.request_index)
                THEN RAISE(ABORT, 'child request index is not durable') END;
        END
        """.trimIndent()

    private val childIdentityTrigger =
        """
        CREATE TRIGGER child_identity_immutable BEFORE UPDATE ON mutation_children
        WHEN NEW.parent_id != OLD.parent_id OR NEW.sequence_number != OLD.sequence_number OR
             NEW.operation_kind != OLD.operation_kind OR NEW.identity_key != OLD.identity_key OR
             NEW.request_index IS NOT OLD.request_index OR NEW.digest_version != OLD.digest_version OR
             NEW.request_sha256 != OLD.request_sha256 OR NEW.item_sha256 IS NOT OLD.item_sha256 OR
             NEW.media_claim_id IS NOT OLD.media_claim_id OR
             NEW.created_at_ms != OLD.created_at_ms OR
             (NEW.state = OLD.state AND (
                NEW.terminal_evidence IS NOT OLD.terminal_evidence OR NEW.updated_at_ms != OLD.updated_at_ms)) OR
             (NEW.state != OLD.state AND NEW.updated_at_ms <= OLD.updated_at_ms)
        BEGIN SELECT RAISE(ABORT, 'child identity is immutable'); END
        """.trimIndent()

    private val childTransitionTrigger =
        """
        CREATE TRIGGER child_state_transition BEFORE UPDATE OF state ON mutation_children
        BEGIN
            SELECT CASE WHEN OLD.state != 'PREPARED' OR NEW.state = 'PREPARED'
                THEN RAISE(ABORT, 'illegal child state transition') END;
            SELECT CASE WHEN NEW.terminal_evidence IS NULL THEN RAISE(ABORT, 'terminal child needs evidence') END;
            SELECT CASE WHEN NEW.state = 'PROVEN_NOT_COMMITTED' AND
                (EXISTS(SELECT 1 FROM provider_attempts WHERE child_id = OLD.id) OR
                 EXISTS(SELECT 1 FROM deck_receipts WHERE child_id = OLD.id) OR
                 EXISTS(SELECT 1 FROM media_receipts WHERE child_id = OLD.id) OR
                 EXISTS(SELECT 1 FROM note_receipts WHERE child_id = OLD.id) OR
                 EXISTS(SELECT 1 FROM card_receipts WHERE child_id = OLD.id))
                THEN RAISE(ABORT, 'proven-not-committed cannot have entry evidence') END;
            SELECT CASE WHEN NEW.state IN ('COMMIT_KNOWN', 'POSTCONDITION_VERIFIED', 'POSTCONDITION_FAILED', 'COMMIT_UNCERTAIN') AND
                NOT EXISTS(SELECT 1 FROM provider_attempts WHERE child_id = OLD.id)
                THEN RAISE(ABORT, 'commit-bearing outcome requires provider entry') END;
            SELECT CASE WHEN NEW.state = 'POSTCONDITION_VERIFIED' AND OLD.operation_kind NOT IN ('DECK_CREATE', 'CARD_DECK_UPDATE')
                THEN RAISE(ABORT, 'operation has no independently reconcilable postcondition') END;
            SELECT CASE WHEN NEW.state = 'POSTCONDITION_FAILED' AND OLD.operation_kind != 'CARD_DECK_UPDATE'
                THEN RAISE(ABORT, 'only card routing has a deterministic failed postcondition') END;
            SELECT CASE WHEN NEW.state = 'POSTCONDITION_VERIFIED' AND OLD.operation_kind = 'DECK_CREATE' AND
                NOT EXISTS(SELECT 1 FROM verified_target_decks WHERE parent_id = OLD.parent_id)
                THEN RAISE(ABORT, 'verified deck child lacks exact durable target snapshot') END;
            SELECT CASE WHEN NEW.state = 'COMMIT_KNOWN' AND (
                (OLD.operation_kind = 'DECK_CREATE' AND NOT EXISTS(SELECT 1 FROM deck_receipts WHERE child_id = OLD.id)) OR
                (OLD.operation_kind = 'MEDIA_INSERT' AND NOT EXISTS(SELECT 1 FROM media_receipts WHERE child_id = OLD.id)) OR
                (OLD.operation_kind = 'NOTE_INSERT' AND NOT EXISTS(SELECT 1 FROM note_receipts WHERE child_id = OLD.id)) OR
                (OLD.operation_kind = 'CARD_DECK_UPDATE' AND NOT EXISTS(SELECT 1 FROM card_receipts WHERE child_id = OLD.id)))
                THEN RAISE(ABORT, 'commit-known requires matching typed receipt') END;
            SELECT CASE WHEN NEW.state = 'COMMIT_KNOWN' AND OLD.operation_kind = 'MEDIA_INSERT' AND (
                (SELECT state FROM media_claims WHERE id = OLD.media_claim_id) != 'STORED' OR
                NOT EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.parent_id AND
                    r.request_index = OLD.request_index AND r.status_kind = 'STORED'))
                THEN RAISE(ABORT, 'media receipt boundary is incomplete') END;
            SELECT CASE WHEN NEW.state = 'COMMIT_KNOWN' AND OLD.operation_kind = 'NOTE_INSERT' AND (
                (SELECT active_note_id FROM parents WHERE id = OLD.parent_id) IS NULL OR
                (SELECT routing_phase FROM parents WHERE id = OLD.parent_id) != 'NOTE_COMMIT_KNOWN')
                THEN RAISE(ABORT, 'note receipt boundary is incomplete') END;
        END
        """.trimIndent()

    private val commandTriggers =
        listOf(
            """
        CREATE TRIGGER deck_command_guard BEFORE INSERT ON deck_commands
        BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'DECK_CREATE'
                THEN RAISE(ABORT, 'wrong deck command operation') END;
            SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM target_expectations t JOIN mutation_children c
                    ON c.parent_id = t.parent_id WHERE c.id = NEW.child_id AND t.expected_deck_name = NEW.deck_name)
                THEN RAISE(ABORT, 'deck command lacks matching target expectation') END;
        END
        """.trimIndent(),
            """
        CREATE TRIGGER media_command_guard BEFORE INSERT ON media_commands
        BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'MEDIA_INSERT'
                THEN RAISE(ABORT, 'wrong media command operation') END;
            SELECT CASE WHEN NEW.asset_id != (SELECT identity_key FROM mutation_children WHERE id = NEW.child_id)
                THEN RAISE(ABORT, 'media command identity mismatch') END;
        END
        """.trimIndent(),
            """
        CREATE TRIGGER note_command_guard BEFORE INSERT ON note_commands
        BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'NOTE_INSERT'
                THEN RAISE(ABORT, 'wrong note command operation') END;
            SELECT CASE WHEN NEW.client_note_id != (SELECT identity_key FROM mutation_children WHERE id = NEW.child_id)
                THEN RAISE(ABORT, 'note command identity mismatch') END;
            SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM target_expectations t
                    JOIN verified_target_decks d ON d.parent_id = t.parent_id
                    JOIN mutation_children c ON c.parent_id = t.parent_id
                    WHERE c.id = NEW.child_id AND t.model_id = NEW.model_id)
                THEN RAISE(ABORT, 'note command lacks matching verified target') END;
        END
        """.trimIndent(),
            """
        CREATE TRIGGER card_command_guard BEFORE INSERT ON card_commands
        BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'CARD_DECK_UPDATE'
                THEN RAISE(ABORT, 'wrong card command operation') END;
            SELECT CASE WHEN CAST(NEW.card_id AS TEXT) != (SELECT identity_key FROM mutation_children WHERE id = NEW.child_id)
                THEN RAISE(ABORT, 'card command identity mismatch') END;
            SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM routing_intents i WHERE i.id = NEW.intent_id AND
                i.parent_id = (SELECT parent_id FROM mutation_children WHERE id = NEW.child_id) AND
                i.request_index = (SELECT request_index FROM mutation_children WHERE id = NEW.child_id) AND
                i.card_id = NEW.card_id AND i.note_id = NEW.note_id AND i.ordinal = NEW.ordinal AND
                i.target_deck_id = NEW.target_deck_id AND i.pre_update_deck_id = NEW.pre_update_deck_id)
                THEN RAISE(ABORT, 'card command differs from routing intent') END;
        END
        """.trimIndent(),
        )

    private val attemptInsertTrigger =
        """
        CREATE TRIGGER provider_attempt_insert_guard BEFORE INSERT ON provider_attempts
        BEGIN
            SELECT CASE WHEN (SELECT state FROM mutation_children WHERE id = NEW.child_id) != 'PREPARED'
                THEN RAISE(ABORT, 'provider entry requires PREPARED child') END;
            SELECT CASE WHEN NEW.attempt_number != 1 + (SELECT count(*) FROM provider_attempts WHERE child_id = NEW.child_id)
                THEN RAISE(ABORT, 'provider attempts must be contiguous') END;
            SELECT CASE WHEN NEW.attempt_number = 2 AND
                (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'CARD_DECK_UPDATE'
                THEN RAISE(ABORT, 'only card recovery may reissue') END;
            SELECT CASE WHEN NEW.attempt_number = 2 AND NOT EXISTS(
                SELECT 1 FROM routing_intents i WHERE i.child_id = NEW.child_id AND i.state = 'UPDATE_PREPARED')
                THEN RAISE(ABORT, 'card recovery reissue lacks durable intent') END;
            SELECT CASE WHEN NEW.attempt_number = 2 AND EXISTS(
                SELECT 1 FROM card_receipts r WHERE r.child_id = NEW.child_id)
                THEN RAISE(ABORT, 'receipt-bearing card command cannot reissue') END;
            SELECT CASE WHEN
                ((SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) = 'DECK_CREATE' AND
                    NOT EXISTS(SELECT 1 FROM deck_commands WHERE child_id = NEW.child_id)) OR
                ((SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) = 'MEDIA_INSERT' AND
                    NOT EXISTS(SELECT 1 FROM media_commands WHERE child_id = NEW.child_id)) OR
                ((SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) = 'NOTE_INSERT' AND
                    NOT EXISTS(SELECT 1 FROM note_commands WHERE child_id = NEW.child_id)) OR
                ((SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) = 'CARD_DECK_UPDATE' AND
                    NOT EXISTS(SELECT 1 FROM card_commands WHERE child_id = NEW.child_id))
                THEN RAISE(ABORT, 'provider entry lacks exact typed command') END;
        END
        """.trimIndent()

    private val immutableAttemptTrigger =
        """
        CREATE TRIGGER provider_attempt_immutable BEFORE UPDATE ON provider_attempts
        BEGIN SELECT RAISE(ABORT, 'provider attempt is immutable'); END
        """.trimIndent()

    private val receiptTriggers =
        listOf(
            """
        CREATE TRIGGER deck_receipt_guard BEFORE INSERT ON deck_receipts BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'DECK_CREATE' OR
                (SELECT state FROM mutation_children WHERE id = NEW.child_id) != 'PREPARED' OR
                NOT EXISTS(SELECT 1 FROM provider_attempts WHERE child_id = NEW.child_id)
                THEN RAISE(ABORT, 'invalid deck receipt attribution') END;
        END
        """.trimIndent(),
            """
        CREATE TRIGGER media_receipt_guard BEFORE INSERT ON media_receipts BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'MEDIA_INSERT' OR
                (SELECT state FROM mutation_children WHERE id = NEW.child_id) != 'PREPARED' OR
                NOT EXISTS(SELECT 1 FROM provider_attempts WHERE child_id = NEW.child_id)
                THEN RAISE(ABORT, 'invalid media receipt attribution') END;
        END
        """.trimIndent(),
            """
        CREATE TRIGGER note_receipt_guard BEFORE INSERT ON note_receipts BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'NOTE_INSERT' OR
                (SELECT state FROM mutation_children WHERE id = NEW.child_id) != 'PREPARED' OR
                NOT EXISTS(SELECT 1 FROM provider_attempts WHERE child_id = NEW.child_id)
                THEN RAISE(ABORT, 'invalid note receipt attribution') END;
        END
        """.trimIndent(),
            """
        CREATE TRIGGER card_receipt_guard BEFORE INSERT ON card_receipts BEGIN
            SELECT CASE WHEN (SELECT operation_kind FROM mutation_children WHERE id = NEW.child_id) != 'CARD_DECK_UPDATE' OR
                (SELECT state FROM mutation_children WHERE id = NEW.child_id) != 'PREPARED' OR
                NOT EXISTS(SELECT 1 FROM provider_attempts WHERE child_id = NEW.child_id)
                THEN RAISE(ABORT, 'invalid card receipt attribution') END;
        END
        """.trimIndent(),
        )

    private val resultInsertTrigger =
        """
        CREATE TRIGGER aligned_result_insert_guard BEFORE INSERT ON aligned_results
        BEGIN
            SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                THEN RAISE(ABORT, 'result parent is not mutable') END;
            SELECT CASE WHEN NEW.request_index != (SELECT count(*) FROM aligned_results WHERE parent_id = NEW.parent_id)
                THEN RAISE(ABORT, 'aligned result must append contiguously') END;
            SELECT CASE WHEN
                ((SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'VERIFY_TARGET' AND NEW.status_kind NOT IN ('VERIFIED', 'FAILED')) OR
                ((SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND NEW.status_kind NOT IN ('STORED', 'FAILED', 'UNCERTAIN', 'NOT_ATTEMPTED')) OR
                ((SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND NEW.status_kind NOT IN ('CREATED', 'DUPLICATE', 'FAILED', 'COMMITTED_FAILED', 'UNCERTAIN', 'NOT_ATTEMPTED'))
                THEN RAISE(ABORT, 'result status does not match operation') END;
            SELECT CASE WHEN (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND
                NEW.status_kind = 'FAILED' AND NEW.error_code != 'MEDIA_STORE_FAILED'
                THEN RAISE(ABORT, 'failed media has wrong error code') END;
            SELECT CASE WHEN NEW.status_kind != 'NOT_ATTEMPTED' AND EXISTS(
                SELECT 1 FROM aligned_results WHERE parent_id = NEW.parent_id AND status_kind = 'NOT_ATTEMPTED')
                THEN RAISE(ABORT, 'non-suffix result follows notAttempted') END;
            SELECT CASE WHEN NEW.status_kind = 'NOT_ATTEMPTED' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND NOT EXISTS(
                SELECT 1 FROM aligned_results WHERE parent_id = NEW.parent_id AND
                    status_kind IN ('FAILED', 'UNCERTAIN', 'COMMITTED_FAILED'))
                THEN RAISE(ABORT, 'notAttempted lacks terminal predecessor') END;
            SELECT CASE WHEN NEW.status_kind = 'VERIFIED' AND
                NOT EXISTS(SELECT 1 FROM verified_target_decks WHERE parent_id = NEW.parent_id)
                THEN RAISE(ABORT, 'verified result lacks durable target') END;
            SELECT CASE WHEN NEW.status_kind = 'STORED' AND NOT EXISTS(
                SELECT 1 FROM mutation_children c
                JOIN media_receipts x ON x.child_id = c.id
                JOIN media_claims m ON m.id = c.media_claim_id
                WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                    c.identity_key = NEW.item_id AND c.state = 'PREPARED' AND
                    x.actual_filename = NEW.actual_filename AND m.state = 'STORED' AND
                    m.actual_filename = NEW.actual_filename)
                THEN RAISE(ABORT, 'stored result lacks receipt boundary proof') END;
            SELECT CASE WHEN NEW.status_kind = 'CREATED' AND NOT EXISTS(
                SELECT 1 FROM mutation_children c JOIN note_receipts x ON x.child_id = c.id
                JOIN parents p ON p.id = c.parent_id
                WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                    c.identity_key = NEW.item_id AND c.state = 'COMMIT_KNOWN' AND
                    x.note_id = NEW.committed_id AND p.active_note_id = NEW.committed_id AND
                    p.routing_phase = 'POSTCHECK_VERIFIED')
                THEN RAISE(ABORT, 'created result lacks note receipt and postcheck proof') END;
            SELECT CASE WHEN NEW.status_kind = 'CREATED' AND (
                NOT EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = NEW.parent_id AND
                    i.request_index = NEW.request_index AND i.note_id = NEW.committed_id) OR
                EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = NEW.parent_id AND
                    i.request_index = NEW.request_index AND (i.state != 'VERIFIED' OR
                    i.note_id != NEW.committed_id OR
                    i.target_deck_id != (SELECT deck_id FROM verified_target_decks WHERE parent_id = NEW.parent_id) OR
                    NOT EXISTS(SELECT 1 FROM target_expectation_templates x WHERE
                        x.parent_id = NEW.parent_id AND x.template_ordinal = i.ordinal))))
                THEN RAISE(ABORT, 'created result lacks complete exact routing proof') END;
            SELECT CASE WHEN NEW.status_kind = 'COMMITTED_FAILED' AND NOT EXISTS(
                SELECT 1 FROM mutation_children c JOIN note_receipts x ON x.child_id = c.id
                WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                    c.identity_key = NEW.item_id AND x.note_id = NEW.committed_id)
                THEN RAISE(ABORT, 'committed-failed result lacks note receipt proof') END;
            SELECT CASE WHEN NEW.status_kind = 'COMMITTED_FAILED' AND NOT EXISTS(
                SELECT 1 FROM remediations m WHERE m.parent_id = NEW.parent_id AND
                    m.kind IN ('NOTE_COMMITTED_FAILED', 'CARD_ROUTING_FAILED'))
                THEN RAISE(ABORT, 'committed-failed result lacks compact remediation') END;
            SELECT CASE WHEN NEW.status_kind = 'COMMITTED_FAILED' AND EXISTS(
                SELECT 1 FROM mutation_children c JOIN card_commands x ON x.child_id = c.id
                WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                    c.state = 'COMMIT_UNCERTAIN' AND x.note_id = NEW.committed_id) AND
                (NEW.error_code != 'POST_COMMIT_UNCERTAIN' OR NEW.error_retryable != 0)
                THEN RAISE(ABORT, 'uncertain card routing requires non-retryable post-commit result') END;
            SELECT CASE WHEN NEW.status_kind = 'FAILED' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND EXISTS(
                    SELECT 1 FROM mutation_children c JOIN provider_attempts a ON a.child_id = c.id
                    WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                        c.identity_key = NEW.item_id AND c.operation_kind = 'MEDIA_INSERT')
                THEN RAISE(ABORT, 'failed media hides provider entry') END;
            SELECT CASE WHEN NEW.status_kind = 'FAILED' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND EXISTS(
                    SELECT 1 FROM mutation_children c JOIN provider_attempts a ON a.child_id = c.id
                    WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                        c.identity_key = NEW.item_id AND c.operation_kind = 'NOTE_INSERT')
                THEN RAISE(ABORT, 'failed note hides provider entry') END;
            SELECT CASE WHEN NEW.status_kind = 'NOT_ATTEMPTED' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND
                (SELECT count(*) FROM mutation_children c
                    WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                        c.identity_key = NEW.item_id AND c.operation_kind = 'MEDIA_INSERT') > 0 AND (
                    (SELECT count(*) FROM mutation_children c
                        WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                            c.identity_key = NEW.item_id AND c.operation_kind = 'MEDIA_INSERT') != 1 OR
                    NOT EXISTS(
                        SELECT 1 FROM mutation_children c JOIN media_claims m ON m.id = c.media_claim_id
                        WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                            c.identity_key = NEW.item_id AND c.operation_kind = 'MEDIA_INSERT' AND
                            c.state = 'PROVEN_NOT_COMMITTED' AND
                            m.state IN ('CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER') AND
                            NOT EXISTS(SELECT 1 FROM provider_attempts a WHERE a.child_id = c.id) AND
                            NOT EXISTS(SELECT 1 FROM media_receipts x WHERE x.child_id = c.id)))
                THEN RAISE(ABORT, 'not-attempted media hides active mutation evidence') END;
            SELECT CASE WHEN NEW.status_kind IN ('DUPLICATE', 'NOT_ATTEMPTED') AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND EXISTS(
                    SELECT 1 FROM mutation_children c WHERE c.parent_id = NEW.parent_id AND
                        c.request_index = NEW.request_index AND c.identity_key = NEW.item_id AND
                        c.operation_kind = 'NOTE_INSERT')
                THEN RAISE(ABORT, 'non-mutating note row hides insert evidence') END;
            SELECT CASE WHEN NEW.status_kind = 'UNCERTAIN' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND NOT EXISTS(
                    SELECT 1 FROM mutation_children c JOIN media_claims m ON m.id = c.media_claim_id
                    WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                        c.identity_key = NEW.item_id AND c.state = 'COMMIT_UNCERTAIN' AND
                        m.state = 'COMMIT_UNCERTAIN')
                THEN RAISE(ABORT, 'uncertain media lacks entry-bearing proof') END;
            SELECT CASE WHEN NEW.status_kind = 'UNCERTAIN' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND NOT EXISTS(
                    SELECT 1 FROM mutation_children c JOIN remediations x ON x.parent_id = c.parent_id AND
                        x.claim_id = c.media_claim_id AND x.kind = 'MEDIA_COMMIT_UNCERTAIN'
                    WHERE c.parent_id = NEW.parent_id AND c.request_index = NEW.request_index AND
                        c.identity_key = NEW.item_id AND c.state = 'COMMIT_UNCERTAIN')
                THEN RAISE(ABORT, 'uncertain media lacks durable remediation') END;
            SELECT CASE WHEN NEW.status_kind = 'UNCERTAIN' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND NOT EXISTS(
                    SELECT 1 FROM mutation_children c WHERE c.parent_id = NEW.parent_id AND
                        c.request_index = NEW.request_index AND c.identity_key = NEW.item_id AND
                        c.operation_kind = 'NOTE_INSERT' AND c.state = 'COMMIT_UNCERTAIN')
                THEN RAISE(ABORT, 'uncertain note lacks entry-bearing proof') END;
            SELECT CASE WHEN NEW.status_kind = 'UNCERTAIN' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND NOT EXISTS(
                    SELECT 1 FROM remediations x WHERE x.parent_id = NEW.parent_id AND
                        x.kind = 'NOTE_COMMIT_UNCERTAIN')
                THEN RAISE(ABORT, 'uncertain note lacks durable remediation') END;
            SELECT CASE WHEN NEW.status_kind = 'FAILED' AND NEW.error_code = 'POST_COMMIT_UNCERTAIN' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'VERIFY_TARGET' AND NOT EXISTS(
                    SELECT 1 FROM mutation_children c JOIN remediations x ON x.parent_id = c.parent_id AND
                        x.kind = 'DECK_COMMIT_UNCERTAIN'
                    WHERE c.parent_id = NEW.parent_id AND c.operation_kind = 'DECK_CREATE' AND
                        c.state = 'COMMIT_UNCERTAIN')
                THEN RAISE(ABORT, 'uncertain deck result lacks child and remediation proof') END;
            SELECT CASE WHEN NEW.status_kind = 'FAILED' AND
                (SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'VERIFY_TARGET' AND EXISTS(
                    SELECT 1 FROM mutation_children c WHERE c.parent_id = NEW.parent_id AND
                        c.operation_kind = 'DECK_CREATE' AND c.state = 'COMMIT_UNCERTAIN') AND
                (NEW.error_code != 'POST_COMMIT_UNCERTAIN' OR NEW.error_retryable != 0)
                THEN RAISE(ABORT, 'uncertain deck cannot be downgraded to a stable verify error') END;
        END
        """.trimIndent()

    private val immutableResultTrigger =
        """
        CREATE TRIGGER aligned_result_immutable BEFORE UPDATE ON aligned_results
        BEGIN SELECT RAISE(ABORT, 'aligned result is immutable'); END
        """.trimIndent()

    private val immutablePayloadTriggers: List<String> by lazy {
        buildList {
            val fullyImmutable =
                listOf(
                    "target_expectation_fields",
                    "target_expectation_templates",
                    "active_notes",
                    "active_note_fields",
                    "active_note_tags",
                    "active_note_media_bindings",
                    "deck_commands",
                    "media_commands",
                    "note_commands",
                    "card_commands",
                    "deck_receipts",
                    "media_receipts",
                    "note_receipts",
                    "card_receipts",
                    "parent_terminal_metadata",
                )
            fullyImmutable.forEach { table ->
                add(
                    """
                    CREATE TRIGGER ${table}_update_forbidden BEFORE UPDATE ON $table
                    BEGIN SELECT RAISE(ABORT, '$table is immutable'); END
                    """.trimIndent(),
                )
            }

            val parentIdTables =
                listOf(
                    "parent_request_items",
                    "target_expectations",
                    "target_expectation_fields",
                    "target_expectation_templates",
                    "verified_target_decks",
                    "mutation_children",
                    "routing_intents",
                    "routing_observations",
                    "aligned_results",
                    "parent_terminal_metadata",
                )
            parentIdTables.forEach { table ->
                add(
                    """
                    CREATE TRIGGER ${table}_delete_guard BEFORE DELETE ON $table
                    WHEN (SELECT state FROM parents WHERE id = OLD.parent_id)
                         NOT IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED')
                    BEGIN SELECT RAISE(ABORT, '$table cannot be deleted before parent finalization'); END
                    """.trimIndent(),
                )
            }

            listOf(
                "active_notes",
                "active_note_fields",
                "active_note_tags",
                "active_note_media_bindings",
            ).forEach { table ->
                add(
                    """
                    CREATE TRIGGER ${table}_delete_guard BEFORE DELETE ON $table
                    WHEN (SELECT state FROM parents WHERE id = OLD.parent_id)
                         NOT IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND
                         NOT EXISTS(SELECT 1 FROM aligned_results r
                             WHERE r.parent_id = OLD.parent_id AND
                                   r.request_index = (SELECT active_request_index FROM parents WHERE id = OLD.parent_id) AND
                                   ((r.status_kind = 'CREATED' AND r.committed_id =
                                        (SELECT active_note_id FROM parents WHERE id = OLD.parent_id) AND
                                        (SELECT routing_phase FROM parents WHERE id = OLD.parent_id) = 'POSTCHECK_VERIFIED') OR
                                    (r.status_kind IN ('FAILED', 'UNCERTAIN') AND
                                        (SELECT active_note_id FROM parents WHERE id = OLD.parent_id) IS NULL AND
                                        (SELECT routing_phase FROM parents WHERE id = OLD.parent_id) = 'NOTE_PENDING') OR
                                    (r.status_kind = 'COMMITTED_FAILED' AND r.committed_id =
                                        (SELECT active_note_id FROM parents WHERE id = OLD.parent_id) AND
                                        (SELECT routing_phase FROM parents WHERE id = OLD.parent_id) IN (
                                            'NOTE_COMMIT_KNOWN', 'NOTE_READBACK_VERIFIED', 'CARDS_DISCOVERED',
                                            'ROUTING', 'ROUTED', 'POSTCHECK_VERIFIED'))))
                    BEGIN SELECT RAISE(ABORT, '$table cannot be deleted before completion or finalization'); END
                    """.trimIndent(),
                )
            }

            val childIdTables =
                listOf(
                    "deck_commands",
                    "media_commands",
                    "note_commands",
                    "card_commands",
                    "provider_attempts",
                    "deck_receipts",
                    "media_receipts",
                    "note_receipts",
                    "card_receipts",
                )
            childIdTables.forEach { table ->
                add(
                    """
                    CREATE TRIGGER ${table}_delete_guard BEFORE DELETE ON $table
                    WHEN (SELECT p.state FROM mutation_children c JOIN parents p ON p.id = c.parent_id
                          WHERE c.id = OLD.child_id) NOT IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED')
                    BEGIN SELECT RAISE(ABORT, '$table cannot be deleted before parent finalization'); END
                    """.trimIndent(),
                )
            }

            add(
                """
                CREATE TRIGGER routing_intent_identity_immutable BEFORE UPDATE ON routing_intents
                WHEN NEW.parent_id != OLD.parent_id OR NEW.request_index != OLD.request_index OR
                     NEW.card_id != OLD.card_id OR NEW.note_id != OLD.note_id OR NEW.ordinal != OLD.ordinal OR
                     NEW.target_deck_id != OLD.target_deck_id OR NEW.pre_update_deck_id != OLD.pre_update_deck_id OR
                     NEW.created_at_ms != OLD.created_at_ms OR
                     (NEW.state = OLD.state AND (
                        NEW.terminal_evidence IS NOT OLD.terminal_evidence OR NEW.updated_at_ms != OLD.updated_at_ms)) OR
                     (NEW.state != OLD.state AND NEW.updated_at_ms <= OLD.updated_at_ms)
                BEGIN SELECT RAISE(ABORT, 'routing intent identity is immutable'); END
                """.trimIndent(),
            )
            add(
                """
                CREATE TRIGGER routing_intent_child_binding_guard BEFORE UPDATE OF child_id ON routing_intents
                WHEN NEW.child_id IS NOT OLD.child_id AND NOT (
                    OLD.child_id IS NULL AND NEW.child_id IS NOT NULL AND OLD.state = 'PENDING' AND
                    NEW.state = 'UPDATE_PREPARED' AND EXISTS(
                        SELECT 1 FROM mutation_children c JOIN card_commands x ON x.child_id = c.id
                        WHERE c.id = NEW.child_id AND c.parent_id = OLD.parent_id AND
                            c.request_index = OLD.request_index AND c.state = 'PREPARED' AND
                            x.intent_id = OLD.id AND x.card_id = OLD.card_id AND x.note_id = OLD.note_id AND
                            x.ordinal = OLD.ordinal AND x.target_deck_id = OLD.target_deck_id AND
                            x.pre_update_deck_id = OLD.pre_update_deck_id))
                BEGIN SELECT RAISE(ABORT, 'routing child binding lacks exact prepared command'); END
                """.trimIndent(),
            )
        }
    }

    private val durableLedgerTriggers: List<String> =
        listOf(
            """
            CREATE TRIGGER parent_delete_forbidden BEFORE DELETE ON parents
            BEGIN SELECT RAISE(ABORT, 'parent audit roots are never deleted'); END
            """.trimIndent(),
            """
            CREATE TRIGGER media_lease_insert_guard BEFORE INSERT ON media_leases
            BEGIN
                SELECT CASE WHEN NEW.state != 'ACTIVE'
                    THEN RAISE(ABORT, 'media lease must start active') END;
                SELECT CASE WHEN (SELECT count(*) FROM media_claims WHERE
                    state IN ('PENDING', 'STORED', 'COMMIT_UNCERTAIN', 'PRESENT_BYTES_VERIFIED')) + NEW.capacity >
                    $GLOBAL_UNRESOLVED_CLAIM_LIMIT
                    THEN RAISE(ABORT, 'media lease exceeds global reserved capacity') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER media_lease_update_guard BEFORE UPDATE ON media_leases
            WHEN NEW.run_id != OLD.run_id OR NEW.capacity != OLD.capacity OR NEW.created_at_ms != OLD.created_at_ms OR
                 OLD.state != 'ACTIVE' OR NEW.state != 'RELEASED' OR NEW.updated_at_ms <= OLD.updated_at_ms OR
                 EXISTS(SELECT 1 FROM media_reservations r WHERE r.lease_id = OLD.id AND r.state = 'RESERVED')
            BEGIN SELECT RAISE(ABORT, 'illegal media lease mutation'); END
            """.trimIndent(),
            """
            CREATE TRIGGER media_lease_delete_forbidden BEFORE DELETE ON media_leases
            BEGIN SELECT RAISE(ABORT, 'media leases are compact durable accounting'); END
            """.trimIndent(),
            """
            CREATE TRIGGER media_reservation_insert_guard BEFORE INSERT ON media_reservations
            BEGIN
                SELECT CASE WHEN NEW.state != 'RESERVED' OR NEW.claim_id IS NOT NULL
                    THEN RAISE(ABORT, 'media reservation must start reversible') END;
                SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM media_leases l WHERE l.id = NEW.lease_id AND
                    l.run_id = NEW.run_id AND l.state = 'ACTIVE')
                    THEN RAISE(ABORT, 'media reservation lacks active run lease') END;
                SELECT CASE WHEN (SELECT count(*) FROM media_reservations r WHERE r.lease_id = NEW.lease_id AND
                    r.state IN ('RESERVED', 'PROMOTED')) >=
                    (SELECT capacity FROM media_leases l WHERE l.id = NEW.lease_id)
                    THEN RAISE(ABORT, 'media lease is fully allocated') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER media_reservation_update_guard BEFORE UPDATE ON media_reservations
            BEGIN
                SELECT CASE WHEN NEW.lease_id != OLD.lease_id OR NEW.run_id != OLD.run_id OR
                    NEW.request_id != OLD.request_id OR NEW.asset_id != OLD.asset_id OR
                    NEW.requested_filename != OLD.requested_filename OR NEW.preferred_name != OLD.preferred_name OR
                    NEW.provider_prefix != OLD.provider_prefix OR NEW.sha256 != OLD.sha256 OR
                    NEW.purpose != OLD.purpose OR NEW.media_kind != OLD.media_kind OR
                    NEW.created_at_ms != OLD.created_at_ms OR NEW.updated_at_ms <= OLD.updated_at_ms
                    THEN RAISE(ABORT, 'media reservation identity is immutable') END;
                SELECT CASE WHEN NOT (
                    (OLD.state = 'RESERVED' AND NEW.state = 'RELEASED' AND NEW.claim_id IS NULL) OR
                    (OLD.state = 'RESERVED' AND NEW.state = 'PROMOTED' AND NEW.claim_id IS NOT NULL AND EXISTS(
                        SELECT 1 FROM media_claims c WHERE c.id = NEW.claim_id AND c.run_id = OLD.run_id AND
                            c.request_id = OLD.request_id AND c.asset_id = OLD.asset_id AND
                            c.requested_filename = OLD.requested_filename AND c.preferred_name = OLD.preferred_name AND
                            c.provider_prefix = OLD.provider_prefix AND c.sha256 = OLD.sha256 AND
                            c.purpose = OLD.purpose AND c.media_kind = OLD.media_kind)))
                    THEN RAISE(ABORT, 'illegal media reservation transition') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER media_reservation_delete_forbidden BEFORE DELETE ON media_reservations
            BEGIN SELECT RAISE(ABORT, 'media reservations are compact durable accounting'); END
            """.trimIndent(),
            """
            CREATE TRIGGER media_claim_insert_guard BEFORE INSERT ON media_claims
            BEGIN
                SELECT CASE WHEN NEW.state != 'PENDING' OR NEW.actual_filename IS NOT NULL OR NEW.compact_evidence IS NOT NULL
                    THEN RAISE(ABORT, 'media claim must start pending without outcome evidence') END;
                SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM media_reservations r JOIN media_leases l ON l.id = r.lease_id
                    WHERE r.run_id = NEW.run_id AND r.request_id = NEW.request_id AND r.asset_id = NEW.asset_id AND
                        r.requested_filename = NEW.requested_filename AND r.preferred_name = NEW.preferred_name AND
                        r.provider_prefix = NEW.provider_prefix AND r.sha256 = NEW.sha256 AND
                        r.purpose = NEW.purpose AND r.media_kind = NEW.media_kind AND
                        r.state = 'RESERVED' AND l.state = 'ACTIVE')
                    THEN RAISE(ABORT, 'media claim lacks exact reserved lease slot') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER media_claim_identity_immutable BEFORE UPDATE ON media_claims
            WHEN NEW.run_id != OLD.run_id OR NEW.request_id != OLD.request_id OR NEW.asset_id != OLD.asset_id OR
                 NEW.requested_filename != OLD.requested_filename OR NEW.preferred_name != OLD.preferred_name OR
                 NEW.provider_prefix != OLD.provider_prefix OR NEW.sha256 != OLD.sha256 OR
                 NEW.purpose != OLD.purpose OR NEW.media_kind != OLD.media_kind OR
                 (OLD.actual_filename IS NOT NULL AND NEW.actual_filename IS NOT OLD.actual_filename) OR
                 NEW.created_at_ms != OLD.created_at_ms OR NEW.state = OLD.state OR
                 NEW.updated_at_ms <= OLD.updated_at_ms
            BEGIN SELECT RAISE(ABORT, 'media claim identity is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER media_claim_attachment_guard BEFORE UPDATE OF state ON media_claims
            WHEN NEW.state = 'ATTACHED_VERIFIED'
            BEGIN
                SELECT CASE WHEN OLD.state NOT IN ('STORED', 'PRESENT_BYTES_VERIFIED') OR NOT EXISTS(
                    SELECT 1 FROM active_note_media_bindings b
                    JOIN active_notes n ON n.parent_id = b.parent_id
                    JOIN parents p ON p.id = n.parent_id
                    JOIN aligned_results r ON r.parent_id = p.id AND r.request_index = n.request_index
                    WHERE b.claim_id = OLD.id AND b.asset_id = OLD.asset_id AND
                        b.actual_filename = OLD.actual_filename AND p.run_id = OLD.run_id AND
                        p.routing_phase = 'POSTCHECK_VERIFIED' AND p.active_note_id = r.committed_id AND
                        r.status_kind = 'CREATED')
                    THEN RAISE(ABORT, 'attachment resolution lacks exact atomic note proof') END;
            END
            """.trimIndent(),
            """
            CREATE TRIGGER media_claim_delete_forbidden BEFORE DELETE ON media_claims
            BEGIN SELECT RAISE(ABORT, 'media claims are never silently removed'); END
            """.trimIndent(),
            """
            CREATE TRIGGER staging_insert_guard BEFORE INSERT ON staging_artifacts
            WHEN NEW.state != 'STAGED' OR NEW.compact_evidence IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'staging artifact must start staged'); END
            """.trimIndent(),
            """
            CREATE TRIGGER staging_identity_immutable BEFORE UPDATE ON staging_artifacts
            WHEN NEW.run_id != OLD.run_id OR NEW.request_id != OLD.request_id OR NEW.asset_id != OLD.asset_id OR
                 NEW.relative_path != OLD.relative_path OR NEW.content_uri != OLD.content_uri OR
                 NEW.package_name != OLD.package_name OR NEW.size_bytes != OLD.size_bytes OR NEW.sha256 != OLD.sha256 OR
                 NEW.created_at_ms != OLD.created_at_ms OR NEW.state = OLD.state OR
                 NEW.updated_at_ms <= OLD.updated_at_ms
            BEGIN SELECT RAISE(ABORT, 'staging identity is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER staging_delete_guard BEFORE DELETE ON staging_artifacts
            WHEN OLD.state != 'CLEANED' OR EXISTS(
                SELECT 1 FROM remediations r WHERE r.staging_id = OLD.id AND r.state = 'OPEN')
            BEGIN SELECT RAISE(ABORT, 'only resolved cleaned staging may be removed'); END
            """.trimIndent(),
            """
            CREATE TRIGGER remediation_insert_guard BEFORE INSERT ON remediations
            WHEN NEW.state != 'OPEN' OR NEW.staging_subject_id IS NOT NEW.staging_id
            BEGIN SELECT RAISE(ABORT, 'remediation must start open'); END
            """.trimIndent(),
            """
            CREATE TRIGGER stored_unattached_remediation_insert_guard BEFORE INSERT ON remediations
            WHEN NEW.kind = 'MEDIA_STORED_UNATTACHED' AND (
                NEW.parent_id IS NULL OR NEW.claim_id IS NULL OR NEW.staging_id IS NOT NULL OR
                NEW.staging_subject_id IS NOT NULL OR NOT EXISTS(
                    SELECT 1 FROM parents p JOIN media_claims c
                        ON c.run_id = p.run_id AND c.request_id = p.request_id
                    WHERE p.id = NEW.parent_id AND c.id = NEW.claim_id AND
                        p.operation_kind = 'STORE_MEDIA' AND p.state = 'RESULT_READY' AND
                        c.state IN ('STORED', 'PRESENT_BYTES_VERIFIED')))
            BEGIN SELECT RAISE(ABORT, 'stored-unattached remediation lacks exact claim proof'); END
            """.trimIndent(),
            """
            CREATE TRIGGER remediation_update_guard BEFORE UPDATE ON remediations
            WHEN NOT (
                (OLD.state = 'OPEN' AND NEW.state = 'RESOLVED' AND
                    NEW.parent_id IS OLD.parent_id AND NEW.claim_id IS OLD.claim_id AND
                    NEW.staging_id IS OLD.staging_id AND NEW.staging_subject_id IS OLD.staging_subject_id AND
                    NEW.kind = OLD.kind AND NEW.summary = OLD.summary AND NEW.created_at_ms = OLD.created_at_ms AND
                    NEW.compact_evidence IS NOT NULL AND NEW.updated_at_ms > OLD.updated_at_ms) OR
                (OLD.state = 'RESOLVED' AND NEW.state = 'RESOLVED' AND
                    NEW.parent_id IS OLD.parent_id AND NEW.claim_id IS OLD.claim_id AND
                    OLD.staging_id IS NOT NULL AND OLD.staging_subject_id = OLD.staging_id AND
                    NEW.staging_id IS NULL AND NEW.staging_subject_id = OLD.staging_subject_id AND
                    NEW.kind = OLD.kind AND NEW.summary = OLD.summary AND
                    NEW.compact_evidence IS OLD.compact_evidence AND NEW.created_at_ms = OLD.created_at_ms AND
                    NEW.updated_at_ms > OLD.updated_at_ms AND EXISTS(
                        SELECT 1 FROM staging_artifacts s WHERE s.id = OLD.staging_id AND s.state = 'CLEANED')))
            BEGIN SELECT RAISE(ABORT, 'illegal remediation mutation'); END
            """.trimIndent(),
            """
            CREATE TRIGGER stored_unattached_remediation_resolution_guard BEFORE UPDATE OF state ON remediations
            WHEN OLD.kind = 'MEDIA_STORED_UNATTACHED' AND NEW.state = 'RESOLVED' AND NOT EXISTS(
                SELECT 1 FROM media_claims c WHERE c.id = OLD.claim_id AND
                    c.state IN ('ATTACHED_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER'))
            BEGIN SELECT RAISE(ABORT, 'stored-unattached remediation resolved before its claim'); END
            """.trimIndent(),
            """
            CREATE TRIGGER remediation_delete_forbidden BEFORE DELETE ON remediations
            BEGIN SELECT RAISE(ABORT, 'remediation evidence is never silently removed'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_parent_audit_insert_guard BEFORE INSERT ON terminal_parent_audit
            WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) != 'RESULT_READY' OR
                 NEW.terminal_variant IS NOT
                    (SELECT variant_kind FROM parent_terminal_metadata WHERE parent_id = NEW.parent_id) OR
                 NEW.result_count != (SELECT count(*) FROM aligned_results WHERE parent_id = NEW.parent_id) OR
                 NEW.child_count != (SELECT count(*) FROM mutation_children WHERE parent_id = NEW.parent_id) OR
                 NEW.finalized_at_ms < (SELECT updated_at_ms FROM parents WHERE id = NEW.parent_id)
            BEGIN SELECT RAISE(ABORT, 'cleanup audit requires exact terminal result'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_result_audit_insert_guard BEFORE INSERT ON terminal_result_audit
            WHEN NOT EXISTS(SELECT 1 FROM terminal_parent_audit a JOIN parents p ON p.id = a.parent_id
                WHERE a.parent_id = NEW.parent_id AND p.state = 'RESULT_READY') OR NOT EXISTS(
                SELECT 1 FROM aligned_results r WHERE r.parent_id = NEW.parent_id AND
                    r.request_index = NEW.request_index AND r.item_id = NEW.item_id AND
                    r.status_kind = NEW.status_kind AND r.committed_id IS NEW.committed_id AND
                    r.actual_filename IS NEW.actual_filename AND r.error_code IS NEW.error_code AND
                    r.error_retryable IS NEW.error_retryable AND r.compact_evidence IS NEW.compact_evidence)
            BEGIN SELECT RAISE(ABORT, 'result audit lacks exact terminal row proof'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_target_audit_insert_guard BEFORE INSERT ON terminal_target_audit
            WHEN NOT EXISTS(SELECT 1 FROM terminal_parent_audit a JOIN parents p ON p.id = a.parent_id
                    WHERE a.parent_id = NEW.parent_id AND p.state = 'RESULT_READY' AND
                        p.operation_kind = 'VERIFY_TARGET') OR
                 NOT EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = NEW.parent_id AND
                    r.request_index = 0 AND r.status_kind = NEW.status_kind) OR
                 NEW.expected_deck_name IS NOT
                    (SELECT expected_deck_name FROM target_expectations WHERE parent_id = NEW.parent_id) OR
                 NEW.model_id IS NOT (SELECT model_id FROM target_expectations WHERE parent_id = NEW.parent_id) OR
                 NEW.model_name IS NOT (SELECT model_name FROM target_expectations WHERE parent_id = NEW.parent_id) OR
                 NEW.verified_deck_id IS NOT
                    (SELECT deck_id FROM verified_target_decks WHERE parent_id = NEW.parent_id) OR
                 NEW.verified_deck_name IS NOT
                    (SELECT deck_name FROM verified_target_decks WHERE parent_id = NEW.parent_id) OR
                 NEW.returned_deck_id IS NOT (SELECT r.deck_id FROM mutation_children c
                    JOIN deck_receipts r ON r.child_id = c.id
                    WHERE c.parent_id = NEW.parent_id AND c.operation_kind = 'DECK_CREATE')
            BEGIN SELECT RAISE(ABORT, 'target audit lacks exact terminal target proof'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_outcome_audit_insert_guard BEFORE INSERT ON terminal_outcome_audit
            WHEN NOT EXISTS(SELECT 1 FROM terminal_parent_audit a JOIN parents p ON p.id = a.parent_id
                WHERE a.parent_id = NEW.parent_id AND p.state = 'RESULT_READY') OR
                 NEW.row_count != (SELECT count(*) FROM aligned_results r WHERE r.parent_id = NEW.parent_id AND
                    r.status_kind = NEW.status_kind)
            BEGIN SELECT RAISE(ABORT, 'outcome audit lacks terminal parent proof'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_receipt_audit_insert_guard BEFORE INSERT ON terminal_receipt_audit
            WHEN NOT EXISTS(SELECT 1 FROM terminal_parent_audit a JOIN parents p ON p.id = a.parent_id
                WHERE a.parent_id = NEW.parent_id AND p.state = 'RESULT_READY') OR
                 NEW.receipt_count != (SELECT count(*) FROM mutation_children c WHERE c.parent_id = NEW.parent_id AND
                    c.operation_kind = NEW.operation_kind AND (
                        EXISTS(SELECT 1 FROM deck_receipts r WHERE r.child_id = c.id) OR
                        EXISTS(SELECT 1 FROM media_receipts r WHERE r.child_id = c.id) OR
                        EXISTS(SELECT 1 FROM note_receipts r WHERE r.child_id = c.id) OR
                        EXISTS(SELECT 1 FROM card_receipts r WHERE r.child_id = c.id)))
            BEGIN SELECT RAISE(ABORT, 'receipt audit lacks terminal parent proof'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_parent_audit_immutable BEFORE UPDATE ON terminal_parent_audit
            BEGIN SELECT RAISE(ABORT, 'terminal parent audit is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_result_audit_immutable BEFORE UPDATE ON terminal_result_audit
            BEGIN SELECT RAISE(ABORT, 'terminal result audit is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_target_audit_immutable BEFORE UPDATE ON terminal_target_audit
            BEGIN SELECT RAISE(ABORT, 'terminal target audit is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_outcome_audit_immutable BEFORE UPDATE ON terminal_outcome_audit
            BEGIN SELECT RAISE(ABORT, 'terminal outcome audit is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_receipt_audit_immutable BEFORE UPDATE ON terminal_receipt_audit
            BEGIN SELECT RAISE(ABORT, 'terminal receipt audit is immutable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_parent_audit_delete_forbidden BEFORE DELETE ON terminal_parent_audit
            BEGIN SELECT RAISE(ABORT, 'terminal parent audit is undeletable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_result_audit_delete_forbidden BEFORE DELETE ON terminal_result_audit
            BEGIN SELECT RAISE(ABORT, 'terminal result audit is undeletable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_target_audit_delete_forbidden BEFORE DELETE ON terminal_target_audit
            BEGIN SELECT RAISE(ABORT, 'terminal target audit is undeletable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_outcome_audit_delete_forbidden BEFORE DELETE ON terminal_outcome_audit
            BEGIN SELECT RAISE(ABORT, 'terminal outcome audit is undeletable'); END
            """.trimIndent(),
            """
            CREATE TRIGGER terminal_receipt_audit_delete_forbidden BEFORE DELETE ON terminal_receipt_audit
            BEGIN SELECT RAISE(ABORT, 'terminal receipt audit is undeletable'); END
            """.trimIndent(),
        )

    private val terminalMetadataTrigger =
        """
        CREATE TRIGGER terminal_metadata_guard BEFORE INSERT ON parent_terminal_metadata
        BEGIN
            SELECT CASE WHEN (SELECT state FROM parents WHERE id = NEW.parent_id) NOT IN ('PREPARED', 'RUNNING')
                THEN RAISE(ABORT, 'terminal metadata parent is not mutable') END;
            SELECT CASE WHEN
                ((SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'VERIFY_TARGET' AND NEW.variant_kind NOT IN ('VERIFY_SUCCESS', 'VERIFY_ERROR')) OR
                ((SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'STORE_MEDIA' AND NEW.variant_kind != 'STORE_MEDIA_RESULT') OR
                ((SELECT operation_kind FROM parents WHERE id = NEW.parent_id) = 'CREATE_NOTES' AND NEW.variant_kind != 'CREATE_NOTES_RESULT')
                THEN RAISE(ABORT, 'terminal variant does not match operation') END;
            SELECT CASE WHEN NEW.variant_kind = 'VERIFY_SUCCESS' AND NEW.error_code IS NOT NULL
                THEN RAISE(ABORT, 'verify success cannot contain error') END;
            SELECT CASE WHEN NEW.variant_kind = 'VERIFY_ERROR' AND NEW.error_code IS NULL
                THEN RAISE(ABORT, 'verify error requires error') END;
        END
        """.trimIndent()

    private val resultReadyTrigger =
        """
        CREATE TRIGGER parent_result_ready_guard BEFORE UPDATE OF state ON parents
        WHEN NEW.state = 'RESULT_READY'
        BEGIN
            SELECT CASE WHEN EXISTS(SELECT 1 FROM mutation_children WHERE parent_id = OLD.id AND state = 'PREPARED')
                THEN RAISE(ABORT, 'RESULT_READY parent retains PREPARED child') END;
            SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM parent_terminal_metadata WHERE parent_id = OLD.id)
                THEN RAISE(ABORT, 'RESULT_READY lacks terminal metadata') END;
            SELECT CASE WHEN (SELECT count(*) FROM aligned_results WHERE parent_id = OLD.id) !=
                                  (SELECT count(*) FROM parent_request_items WHERE parent_id = OLD.id)
                THEN RAISE(ABORT, 'RESULT_READY lacks exact aligned coverage') END;
            SELECT CASE WHEN NEW.operation_kind = 'VERIFY_TARGET' AND
                ((SELECT variant_kind FROM parent_terminal_metadata WHERE parent_id = OLD.id) = 'VERIFY_SUCCESS') !=
                 EXISTS(SELECT 1 FROM aligned_results WHERE parent_id = OLD.id AND status_kind = 'VERIFIED')
                THEN RAISE(ABORT, 'verify payload source and metadata disagree') END;
            SELECT CASE WHEN NEW.operation_kind = 'VERIFY_TARGET' AND
                (SELECT variant_kind FROM parent_terminal_metadata WHERE parent_id = OLD.id) = 'VERIFY_SUCCESS' AND
                NOT EXISTS(SELECT 1 FROM verified_target_decks WHERE parent_id = OLD.id)
                THEN RAISE(ABORT, 'verify success lacks target snapshot') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aligned_results WHERE parent_id = OLD.id AND status_kind = 'UNCERTAIN') AND
                NOT EXISTS(SELECT 1 FROM parent_terminal_metadata WHERE parent_id = OLD.id AND
                    error_code = 'POST_COMMIT_UNCERTAIN' AND error_retryable = 0)
                THEN RAISE(ABORT, 'uncertain row lacks non-retryable parent uncertainty') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aligned_results WHERE parent_id = OLD.id AND
                    status_kind = 'COMMITTED_FAILED' AND error_code = 'POST_COMMIT_UNCERTAIN') AND
                NOT EXISTS(SELECT 1 FROM parent_terminal_metadata WHERE parent_id = OLD.id AND
                    error_code = 'POST_COMMIT_UNCERTAIN' AND error_retryable = 0)
                THEN RAISE(ABORT, 'committed uncertainty lacks matching parent uncertainty') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aligned_results r JOIN parent_terminal_metadata m ON m.parent_id = r.parent_id
                    JOIN parents p ON p.id = r.parent_id
                    WHERE r.parent_id = OLD.id AND p.operation_kind IN ('VERIFY_TARGET', 'CREATE_NOTES') AND r.status_kind = 'FAILED' AND
                    (r.error_code != m.error_code OR r.error_message != m.error_message OR r.error_retryable != m.error_retryable))
                THEN RAISE(ABORT, 'terminal failed row and parent error disagree') END;
            SELECT CASE WHEN NEW.operation_kind = 'STORE_MEDIA' AND
                (EXISTS(SELECT 1 FROM parent_terminal_metadata m WHERE m.parent_id = OLD.id AND m.error_code IS NOT NULL) !=
                 EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND
                    r.status_kind IN ('UNCERTAIN', 'NOT_ATTEMPTED')))
                THEN RAISE(ABORT, 'media stop/uncertainty carrier and top error disagree') END;
            SELECT CASE WHEN NEW.operation_kind = 'STORE_MEDIA' AND EXISTS(
                    SELECT 1 FROM parent_terminal_metadata m WHERE m.parent_id = OLD.id AND
                        m.error_code IS NOT NULL AND m.error_code != 'POST_COMMIT_UNCERTAIN') AND
                NOT EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND r.status_kind = 'NOT_ATTEMPTED')
                THEN RAISE(ABORT, 'stable media stop lacks notAttempted suffix') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aligned_results r JOIN parent_terminal_metadata m ON m.parent_id = r.parent_id
                    WHERE r.parent_id = OLD.id AND r.status_kind = 'COMMITTED_FAILED' AND
                    (r.error_code != m.error_code OR r.error_message != m.error_message OR r.error_retryable != m.error_retryable))
                THEN RAISE(ABORT, 'committed-failed row and parent error disagree') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aligned_results WHERE parent_id = OLD.id AND
                    status_kind IN ('STORED', 'CREATED', 'COMMITTED_FAILED')) AND
                EXISTS(SELECT 1 FROM parent_terminal_metadata WHERE parent_id = OLD.id AND error_retryable = 1)
                THEN RAISE(ABORT, 'known writes cannot have retryable terminal error') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aligned_results WHERE parent_id = OLD.id AND
                    status_kind = 'COMMITTED_FAILED' AND error_code = 'CANCELLED')
                THEN RAISE(ABORT, 'known committed failure cannot be cancellation') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM parent_terminal_metadata WHERE parent_id = OLD.id AND
                    error_code = 'POST_COMMIT_UNCERTAIN') AND NOT EXISTS(SELECT 1 FROM aligned_results WHERE
                    parent_id = OLD.id AND (status_kind = 'UNCERTAIN' OR
                    (status_kind = 'FAILED' AND error_code = 'POST_COMMIT_UNCERTAIN' AND
                        (SELECT operation_kind FROM parents WHERE id = OLD.id) = 'VERIFY_TARGET') OR
                    (status_kind = 'COMMITTED_FAILED' AND error_code = 'POST_COMMIT_UNCERTAIN')))
                THEN RAISE(ABORT, 'post-commit uncertainty lacks row carrier') END;
        END
        """.trimIndent()

    private val phaseTransitionTrigger =
        """
        CREATE TRIGGER note_phase_transition BEFORE UPDATE OF routing_phase ON parents
        WHEN NEW.routing_phase IS NOT OLD.routing_phase
        BEGIN
            SELECT CASE WHEN NOT (
                (OLD.routing_phase IS NULL AND NEW.routing_phase = 'NOTE_PENDING') OR
                (OLD.routing_phase = 'NOTE_PENDING' AND NEW.routing_phase = 'NOTE_COMMIT_KNOWN') OR
                (OLD.routing_phase = 'NOTE_COMMIT_KNOWN' AND NEW.routing_phase = 'NOTE_READBACK_VERIFIED') OR
                (OLD.routing_phase = 'NOTE_READBACK_VERIFIED' AND NEW.routing_phase = 'CARDS_DISCOVERED') OR
                (OLD.routing_phase = 'CARDS_DISCOVERED' AND NEW.routing_phase = 'ROUTING' AND
                    EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = OLD.id AND
                        i.request_index = OLD.active_request_index AND i.note_id = OLD.active_note_id) AND
                    NOT EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = OLD.id AND
                        i.request_index = OLD.active_request_index AND (i.note_id != OLD.active_note_id OR
                        i.state != 'PENDING' OR i.target_deck_id !=
                            (SELECT deck_id FROM verified_target_decks WHERE parent_id = OLD.id) OR
                        NOT EXISTS(SELECT 1 FROM target_expectation_templates x WHERE
                            x.parent_id = OLD.id AND x.template_ordinal = i.ordinal)))) OR
                (OLD.routing_phase = 'ROUTING' AND NEW.routing_phase = 'ROUTED' AND
                    EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = OLD.id AND
                        i.request_index = OLD.active_request_index AND i.note_id = OLD.active_note_id) AND
                    NOT EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = OLD.id AND
                        i.request_index = OLD.active_request_index AND (i.note_id != OLD.active_note_id OR
                        i.state != 'VERIFIED' OR i.target_deck_id !=
                            (SELECT deck_id FROM verified_target_decks WHERE parent_id = OLD.id) OR
                        NOT EXISTS(SELECT 1 FROM target_expectation_templates x WHERE
                            x.parent_id = OLD.id AND x.template_ordinal = i.ordinal)))) OR
                (OLD.routing_phase = 'ROUTED' AND NEW.routing_phase = 'POSTCHECK_VERIFIED' AND
                    EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = OLD.id AND
                        i.request_index = OLD.active_request_index AND i.note_id = OLD.active_note_id) AND
                    NOT EXISTS(SELECT 1 FROM routing_intents i WHERE i.parent_id = OLD.id AND
                        i.request_index = OLD.active_request_index AND (i.note_id != OLD.active_note_id OR
                        i.state != 'VERIFIED' OR i.target_deck_id !=
                            (SELECT deck_id FROM verified_target_decks WHERE parent_id = OLD.id) OR
                        NOT EXISTS(SELECT 1 FROM target_expectation_templates x WHERE
                            x.parent_id = OLD.id AND x.template_ordinal = i.ordinal)))) OR
                (OLD.routing_phase = 'POSTCHECK_VERIFIED' AND NEW.routing_phase IS NULL AND
                    EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND
                        r.request_index = OLD.active_request_index AND r.status_kind = 'CREATED' AND
                        r.committed_id = OLD.active_note_id)) OR
                (OLD.routing_phase = 'NOTE_PENDING' AND NEW.routing_phase IS NULL AND OLD.active_note_id IS NULL AND
                    EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND
                        r.request_index = OLD.active_request_index AND r.status_kind IN ('FAILED', 'UNCERTAIN'))) OR
                (OLD.routing_phase IN ('NOTE_COMMIT_KNOWN', 'NOTE_READBACK_VERIFIED', 'CARDS_DISCOVERED',
                        'ROUTING', 'ROUTED', 'POSTCHECK_VERIFIED') AND NEW.routing_phase IS NULL AND
                    EXISTS(SELECT 1 FROM aligned_results r WHERE r.parent_id = OLD.id AND
                        r.request_index = OLD.active_request_index AND r.status_kind = 'COMMITTED_FAILED' AND
                        r.committed_id = OLD.active_note_id)) OR
                (NEW.routing_phase IS NULL AND NEW.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED')))
                THEN RAISE(ABORT, 'illegal note phase transition') END;
            SELECT CASE WHEN NEW.routing_phase = 'NOTE_COMMIT_KNOWN' AND (
                NEW.active_note_id IS NULL OR NOT EXISTS(SELECT 1 FROM mutation_children c JOIN note_receipts r ON r.child_id = c.id
                    WHERE c.parent_id = OLD.id AND c.state = 'PREPARED' AND r.note_id = NEW.active_note_id))
                THEN RAISE(ABORT, 'note phase lacks typed receipt') END;
        END
        """.trimIndent()

    private val routingTransitionTrigger =
        """
        CREATE TRIGGER routing_state_transition BEFORE UPDATE OF state ON routing_intents
        BEGIN
            SELECT CASE WHEN NOT (
                (OLD.state = 'PENDING' AND NEW.state IN ('UPDATE_PREPARED', 'VERIFIED', 'FAILED')) OR
                (OLD.state = 'UPDATE_PREPARED' AND NEW.state IN ('VERIFIED', 'FAILED', 'COMMIT_UNCERTAIN')))
                THEN RAISE(ABORT, 'illegal routing transition') END;
            SELECT CASE WHEN OLD.state = 'PENDING' AND NEW.state = 'VERIFIED' AND
                (OLD.child_id IS NOT NULL OR NOT EXISTS(
                    SELECT 1 FROM routing_observations o WHERE o.intent_id = OLD.id AND
                        o.parent_id = OLD.parent_id AND o.request_index = OLD.request_index AND
                        o.card_id = OLD.card_id AND o.note_id = OLD.note_id AND o.ordinal = OLD.ordinal AND
                        o.deck_id = OLD.target_deck_id))
                THEN RAISE(ABORT, 'direct routing verification is not exact target evidence') END;
            SELECT CASE WHEN OLD.state = 'PENDING' AND NEW.state = 'FAILED' AND
                (OLD.child_id IS NOT NULL OR NOT EXISTS(
                    SELECT 1 FROM routing_observations o WHERE o.intent_id = OLD.id AND
                        o.parent_id = OLD.parent_id AND o.request_index = OLD.request_index AND
                        (o.card_id != OLD.card_id OR o.note_id != OLD.note_id OR o.ordinal != OLD.ordinal OR
                            o.deck_id != OLD.target_deck_id)))
                THEN RAISE(ABORT, 'direct routing failure lacks exact observed drift') END;
            SELECT CASE WHEN OLD.state = 'UPDATE_PREPARED' AND NOT EXISTS(
                SELECT 1 FROM mutation_children c WHERE c.id = OLD.child_id AND (
                    (NEW.state = 'VERIFIED' AND c.state = 'POSTCONDITION_VERIFIED') OR
                    (NEW.state = 'FAILED' AND c.state IN ('PROVEN_NOT_COMMITTED', 'POSTCONDITION_FAILED')) OR
                    (NEW.state = 'COMMIT_UNCERTAIN' AND c.state = 'COMMIT_UNCERTAIN')))
                THEN RAISE(ABORT, 'routing outcome differs from typed child evidence') END;
        END
        """.trimIndent()

    private val routingInsertTrigger =
        """
        CREATE TRIGGER routing_intent_insert_guard BEFORE INSERT ON routing_intents
        BEGIN
            SELECT CASE WHEN NEW.state != 'PENDING' OR NEW.child_id IS NOT NULL OR NEW.terminal_evidence IS NOT NULL
                THEN RAISE(ABORT, 'routing intent must start pending without mutation evidence') END;
            SELECT CASE WHEN NOT EXISTS(
                SELECT 1 FROM parents p
                JOIN target_expectations t ON t.parent_id = p.id
                JOIN verified_target_decks d ON d.parent_id = p.id
                JOIN target_expectation_templates x ON x.parent_id = p.id AND x.template_ordinal = NEW.ordinal
                WHERE p.id = NEW.parent_id AND p.operation_kind = 'CREATE_NOTES' AND
                    p.state IN ('PREPARED', 'RUNNING') AND p.routing_phase = 'CARDS_DISCOVERED' AND
                    p.active_request_index = NEW.request_index AND p.active_note_id = NEW.note_id AND
                    d.deck_id = NEW.target_deck_id)
                THEN RAISE(ABORT, 'routing intent differs from durable active target') END;
        END
        """.trimIndent()

    private val routingObservationInsertTrigger =
        """
        CREATE TRIGGER routing_observation_insert_guard BEFORE INSERT ON routing_observations
        BEGIN
            SELECT CASE WHEN NOT EXISTS(
                SELECT 1 FROM routing_intents i JOIN parents p ON p.id = i.parent_id
                WHERE i.id = NEW.intent_id AND i.parent_id = NEW.parent_id AND
                    i.request_index = NEW.request_index AND i.child_id IS NULL AND i.state = 'PENDING' AND
                    p.state IN ('PREPARED', 'RUNNING') AND p.routing_phase = 'ROUTING' AND
                    p.active_request_index = i.request_index AND p.active_note_id = i.note_id AND
                    NEW.observed_at_ms >= i.created_at_ms)
                THEN RAISE(ABORT, 'routing observation differs from the active childless intent') END;
        END
        """.trimIndent()

    private val routingObservationImmutableTrigger =
        """
        CREATE TRIGGER routing_observation_update_forbidden BEFORE UPDATE ON routing_observations
        BEGIN SELECT RAISE(ABORT, 'routing observation is immutable'); END
        """.trimIndent()

    private val claimTransitionTrigger =
        """
        CREATE TRIGGER claim_state_transition BEFORE UPDATE OF state ON media_claims
        BEGIN
            SELECT CASE WHEN NOT (
                (OLD.state = 'PENDING' AND NEW.state IN ('STORED', 'COMMIT_UNCERTAIN', 'PRESENT_BYTES_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')) OR
                (OLD.state = 'STORED' AND NEW.state IN ('PRESENT_BYTES_VERIFIED', 'ATTACHED_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')) OR
                (OLD.state = 'COMMIT_UNCERTAIN' AND NEW.state IN ('PRESENT_BYTES_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')) OR
                (OLD.state = 'PRESENT_BYTES_VERIFIED' AND NEW.state IN ('ATTACHED_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')))
                THEN RAISE(ABORT, 'illegal media claim transition') END;
            SELECT CASE WHEN NEW.compact_evidence IS NULL
                THEN RAISE(ABORT, 'media claim transition needs compact evidence') END;
        END
        """.trimIndent()

    /** Keeps orphan remediation and the only legitimate cross-parent attachment path coupled. */
    private val storedUnattachedClaimGuard =
        """
        CREATE TRIGGER stored_unattached_claim_guard BEFORE UPDATE OF state ON media_claims
        WHEN EXISTS(
            SELECT 1 FROM remediations r WHERE r.claim_id = OLD.id AND
                r.kind = 'MEDIA_STORED_UNATTACHED' AND r.state = 'OPEN')
        BEGIN
            SELECT CASE WHEN NEW.state NOT IN (
                'PRESENT_BYTES_VERIFIED', 'ATTACHED_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')
                THEN RAISE(ABORT, 'stored-unattached claim cannot leave its remediation stale') END;
            SELECT CASE WHEN NEW.state = 'PRESENT_BYTES_VERIFIED' AND NOT EXISTS(
                SELECT 1 FROM active_note_media_bindings b
                JOIN active_notes n ON n.parent_id = b.parent_id
                JOIN parents p ON p.id = n.parent_id
                WHERE b.claim_id = OLD.id AND b.asset_id = OLD.asset_id AND
                    b.actual_filename = OLD.actual_filename AND p.run_id = OLD.run_id AND
                    p.state IN ('PREPARED', 'RUNNING'))
                THEN RAISE(ABORT, 'stored-unattached byte proof lacks a durable note binding') END;
        END
        """.trimIndent()

    private val storedUnattachedClaimResolutionTrigger =
        """
        CREATE TRIGGER stored_unattached_claim_resolution AFTER UPDATE OF state ON media_claims
        WHEN NEW.state IN ('ATTACHED_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')
        BEGIN
            UPDATE remediations
            SET state = 'RESOLVED', compact_evidence = NEW.compact_evidence,
                updated_at_ms = CASE
                    WHEN updated_at_ms >= NEW.updated_at_ms THEN updated_at_ms + 1
                    ELSE NEW.updated_at_ms
                END
            WHERE claim_id = OLD.id AND kind = 'MEDIA_STORED_UNATTACHED' AND state = 'OPEN';
        END
        """.trimIndent()

    private val stagingTransitionTrigger =
        """
        CREATE TRIGGER staging_state_transition BEFORE UPDATE OF state ON staging_artifacts
        BEGIN
            SELECT CASE WHEN NOT (
                (OLD.state = 'STAGED' AND NEW.state IN ('GRANTED', 'CLEANUP_PENDING', 'CLEANED', 'QUARANTINED')) OR
                (OLD.state = 'GRANTED' AND NEW.state IN ('CLEANUP_PENDING', 'CLEANED', 'QUARANTINED')) OR
                (OLD.state = 'CLEANUP_PENDING' AND NEW.state IN ('CLEANED', 'QUARANTINED')) OR
                (OLD.state = 'QUARANTINED' AND NEW.state = 'CLEANED'))
                THEN RAISE(ABORT, 'illegal staging transition') END;
            SELECT CASE WHEN NEW.compact_evidence IS NULL
                THEN RAISE(ABORT, 'staging transition needs compact evidence') END;
        END
        """.trimIndent()
}
