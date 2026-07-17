package com.ankiminer.android.anki.provider

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnkiMinerModelProvisioningJournalTest {
    @Test
    fun `codec round trips every valid phase in one canonical form`() {
        val records =
            listOf(
                record(AnkiMinerModelProvisioningPhase.PREPARED),
                record(AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED),
                record(AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED, 42L, SNAPSHOT_SHA),
                record(AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED, 42L, SNAPSHOT_SHA),
                record(AnkiMinerModelProvisioningPhase.COMPLETE, 42L, SNAPSHOT_SHA),
            )

        records.forEach { expected ->
            val wire = AnkiMinerModelProvisioningJournalCodec.encode(expected)
            val actual = AnkiMinerModelProvisioningJournalCodec.decode(wire)
            assertEquals(expected, actual)
            assertArrayEquals(wire, AnkiMinerModelProvisioningJournalCodec.encode(actual))
        }
    }

    @Test
    fun `codec rejects corrupt noncanonical and forward version records`() {
        listOf(
            "",
            "anki-miner-model-provisioning-journal-v2\n$CONTRACT_SHA\nPREPARED\n\n\n",
            "anki-miner-model-provisioning-journal-v1\n$CONTRACT_SHA\nUNKNOWN\n\n\n",
            "anki-miner-model-provisioning-journal-v1\n$CONTRACT_SHA\nCOMPLETE\n042\n$SNAPSHOT_SHA\n",
            "anki-miner-model-provisioning-journal-v1\n$CONTRACT_SHA\nCOMPLETE\n42\n\n",
            "anki-miner-model-provisioning-journal-v1\n$CONTRACT_SHA\nPREPARED\n\n\nextra\n",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) {
                AnkiMinerModelProvisioningJournalCodec.decode(
                    raw.toByteArray(StandardCharsets.US_ASCII),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnkiMinerModelProvisioningJournalCodec.decode(byteArrayOf(0xff.toByte()))
        }
    }

    @Test
    fun `transition contract never clears entry evidence or changes model identity`() {
        val prepared = record(AnkiMinerModelProvisioningPhase.PREPARED)
        val createEntered = record(AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED)
        val base = record(AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED, 42L, SNAPSHOT_SHA)
        val templateEntered =
            record(AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED, 42L, SNAPSHOT_SHA)
        val complete = record(AnkiMinerModelProvisioningPhase.COMPLETE, 42L, COMPLETE_SHA)
        val nextPrepared =
            record(AnkiMinerModelProvisioningPhase.PREPARED).copy(
                contractSha256 = "4444444444444444444444444444444444444444444444444444444444444444",
            )

        requireAllowedTransition(null, prepared)
        requireAllowedTransition(prepared, createEntered)
        requireAllowedTransition(createEntered, base)
        requireAllowedTransition(base, templateEntered)
        requireAllowedTransition(templateEntered, templateEntered)
        requireAllowedTransition(templateEntered, complete)
        requireAllowedTransition(complete, record(AnkiMinerModelProvisioningPhase.PREPARED))
        requireAllowedTransition(complete, nextPrepared)

        listOf(
            { requireAllowedTransition(null, createEntered) },
            { requireAllowedTransition(createEntered, prepared) },
            {
                requireAllowedTransition(
                    base,
                    templateEntered.copy(modelId = 43L),
                )
            },
            {
                requireAllowedTransition(
                    templateEntered,
                    templateEntered.copy(snapshotSha256 = COMPLETE_SHA),
                )
            },
            { requireAllowedTransition(complete, complete) },
            {
                requireAllowedTransition(
                    createEntered,
                    nextPrepared,
                )
            },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }

    private fun record(
        phase: AnkiMinerModelProvisioningPhase,
        modelId: Long? = null,
        snapshotSha: String? = null,
    ) =
        AnkiMinerModelProvisioningRecord(
            contractSha256 = CONTRACT_SHA,
            phase = phase,
            modelId = modelId,
            snapshotSha256 = snapshotSha,
        )

    private companion object {
        const val CONTRACT_SHA = "1111111111111111111111111111111111111111111111111111111111111111"
        const val SNAPSHOT_SHA = "2222222222222222222222222222222222222222222222222222222222222222"
        const val COMPLETE_SHA = "3333333333333333333333333333333333333333333333333333333333333333"
    }
}
