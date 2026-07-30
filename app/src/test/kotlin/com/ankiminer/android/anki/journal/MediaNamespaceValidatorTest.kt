package com.ankiminer.android.anki.journal

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamespaceValidatorTest {
    @Test
    fun leaseAndGlobalCapacityEdgesAreFixedAtEightAndSixteenThousand() {
        assertEquals(8_000, MEDIA_LEASE_CAPACITY)
        assertEquals(16_000, GLOBAL_UNRESOLVED_CLAIM_LIMIT)

        JournalCapacityLimits.forTests(leaseCapacity = 7_999, globalLimit = 15_999)
        JournalCapacityLimits.forTests(leaseCapacity = 8_000, globalLimit = 15_999)
        JournalCapacityLimits.forTests(leaseCapacity = 8_000, globalLimit = 16_000)
        assertThrows(IllegalArgumentException::class.java) {
            JournalCapacityLimits.forTests(leaseCapacity = 8_001, globalLimit = 16_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JournalCapacityLimits.forTests(leaseCapacity = 8_000, globalLimit = 16_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JournalCapacityLimits.forTests(leaseCapacity = 8_000, globalLimit = 7_999)
        }
    }

    @Test
    fun leaseAdmissionReservesTheWholeEightThousandSlotRunAtExactGlobalBoundaries() {
        MediaCapacityPolicy.requireLeaseAdmission(7_999, null, 8_000, 16_000)
        MediaCapacityPolicy.requireLeaseAdmission(8_000, null, 8_000, 16_000)
        assertThrows(JournalInvariantViolation::class.java) {
            MediaCapacityPolicy.requireLeaseAdmission(8_001, null, 8_000, 16_000)
        }
        assertThrows(JournalInvariantViolation::class.java) {
            MediaCapacityPolicy.requireLeaseAdmission(0, 8_000, 8_000, 16_000)
        }
        assertThrows(JournalInvariantViolation::class.java) {
            MediaCapacityPolicy.requireLeaseAdmission(8_000, 0, 8_000, 16_000)
        }
    }

    @Test
    fun reservationPromotionAndReleaseUseOneLeaseSlot() {
        assertEquals(8_000, MediaCapacityPolicy.unusedSlots(8_000, 0))
        assertEquals(7_999, MediaCapacityPolicy.unusedSlots(8_000, 1))
        assertEquals(0, MediaCapacityPolicy.unusedSlots(8_000, 8_000))
        assertThrows(JournalCorruptionException::class.java) {
            MediaCapacityPolicy.unusedSlots(8_000, 8_001)
        }
    }

    @Test
    fun synchronousDurabilityPolicyHasAnExplicitApiThirtyBoundary() {
        assertEquals(
            SqliteSynchronousConfiguration.PRIMARY_CONNECTION,
            JournalSqliteDurabilityPolicy.synchronousConfiguration(26),
        )
        assertEquals(
            SqliteSynchronousConfiguration.PRIMARY_CONNECTION,
            JournalSqliteDurabilityPolicy.synchronousConfiguration(29),
        )
        assertEquals(
            SqliteSynchronousConfiguration.ALL_CONNECTIONS,
            JournalSqliteDurabilityPolicy.synchronousConfiguration(30),
        )
    }

    @Test
    fun exactDirectNamesAndProviderFamiliesMustBeDisjointAcrossOwners() {
        val ownerA = MediaNamespaceOwner("run-a", "asset-a")
        val ownerB = MediaNamespaceOwner("run-b", "asset-b")

        assertThrows(JournalInvariantViolation::class.java) {
            MediaNamespaceValidator.requireDisjoint(
                listOf(
                    MediaNamespaceLock(ownerA, "shared.ogg", "alpha_"),
                    MediaNamespaceLock(ownerB, "shared.ogg", "beta_"),
                ),
            )
        }
        assertThrows(JournalInvariantViolation::class.java) {
            MediaNamespaceValidator.requireDisjoint(
                listOf(
                    MediaNamespaceLock(ownerA, "alpha.ogg", "shared_"),
                    MediaNamespaceLock(ownerB, "shared_42.ogg", "beta_"),
                ),
            )
        }
        assertThrows(JournalInvariantViolation::class.java) {
            MediaNamespaceValidator.requireDisjoint(
                listOf(
                    MediaNamespaceLock(ownerA, "alpha.ogg", "family_"),
                    MediaNamespaceLock(ownerB, "beta.ogg", "family_nested_"),
                ),
            )
        }
    }

    @Test
    fun oneOwnerMayHoldItsOwnFamilyWhileUnrelatedOwnersRemainDisjoint() {
        val ownerA = MediaNamespaceOwner("run-a", "asset-a")
        val ownerB = MediaNamespaceOwner("run-b", "asset-b")
        MediaNamespaceValidator.requireDisjoint(
            listOf(
                MediaNamespaceLock(ownerA, "family_1.ogg", "family_"),
                MediaNamespaceLock(ownerA, "family_2.ogg", "family_nested_"),
                MediaNamespaceLock(ownerB, "other.ogg", "other_"),
            ),
        )
    }

    @Test
    fun reStoringContentAddressedMediaCollidesWithAnEarlierRunsUnresolvedClaim() {
        // The reachable shape behind Issue #6, in the names the field actually produces. Card media is
        // content-addressed ({stem}_{sha1[:12]}), and AnkiDroid stores it as {preferredName}_{random},
        // so a claim an earlier run left unresolved — stored but never attached to a note — holds a
        // namespace family that a later run's reservation for the same bytes always falls inside.
        //
        // Rejection is correct: the journal cannot tell two owners apart by content, and renaming
        // another owner's stored file would corrupt the collection. It stops being fatal to the run at
        // the layer above, where JournalBackedMediaMutationService turns it into a media_store_failed
        // row instead of a top-level internal_error.
        val earlierRun = MediaNamespaceOwner("run-1", "asset-1")
        val laterRun = MediaNamespaceOwner("run-2", "asset-2")

        assertThrows(JournalInvariantViolation::class.java) {
            MediaNamespaceValidator.requireDisjoint(
                listOf(
                    MediaNamespaceLock(earlierRun, "本好き_ab12cd34ef56_1739.opus", "本好き_ab12cd34ef56_"),
                    MediaNamespaceLock(laterRun, "本好き_ab12cd34ef56.opus", "本好き_ab12cd34ef56_"),
                ),
            )
        }

        // Dictionary media reaches the same wall through its hashed prefix rather than its basename.
        assertThrows(JournalInvariantViolation::class.java) {
            MediaNamespaceValidator.requireDisjoint(
                listOf(
                    MediaNamespaceLock(
                        earlierRun,
                        "anki_miner_dict_${"a".repeat(64)}_884.png",
                        "anki_miner_dict_${"a".repeat(64)}_",
                    ),
                    MediaNamespaceLock(
                        laterRun,
                        "image.png",
                        "anki_miner_dict_${"a".repeat(64)}_",
                    ),
                ),
            )
        }
    }

    @Test
    fun globalNamespaceBoundaryAcceptsSixteenThousandAndRejectsOneMore() {
        MediaNamespaceValidator.requireDisjoint(disjointLocks(15_999))
        MediaNamespaceValidator.requireDisjoint(disjointLocks(16_000))
        assertThrows(JournalInvariantViolation::class.java) {
            MediaNamespaceValidator.requireDisjoint(disjointLocks(16_001))
        }
    }

    @Test
    fun sixteenThousandLockValidationHasSortingScaleRuntime() {
        val locks = disjointLocks(16_000).asReversed()
        val elapsedMs = measureTimeMillis { MediaNamespaceValidator.requireDisjoint(locks) }
        assertTrue("16k namespace validation took ${elapsedMs}ms", elapsedMs < 10_000)
    }

    private fun disjointLocks(count: Int): List<MediaNamespaceLock> =
        List(count) { index ->
            val suffix = index.toString().padStart(5, '0')
            MediaNamespaceLock(
                owner = MediaNamespaceOwner("run-$suffix", "asset-$suffix"),
                directFilename = "direct-$suffix.ogg",
                providerPrefix = "provider-$suffix-",
            )
        }
}
