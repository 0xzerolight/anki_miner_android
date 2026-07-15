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
