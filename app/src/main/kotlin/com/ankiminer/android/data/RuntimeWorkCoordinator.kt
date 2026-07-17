package com.ankiminer.android.data

/** Process-wide exclusion between immutable mining jobs and resource publication. */
internal class RuntimeWorkCoordinator {
    enum class Kind {
        MINING,
        RESOURCE,
        ANKI_SETUP,
    }

    class Lease internal constructor(
        private val owner: RuntimeWorkCoordinator,
        internal val kind: Kind,
        internal val generation: Long,
    ) : AutoCloseable {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            synchronized(this) {
                if (closed) return
                owner.release(this, generation)
                closed = true
            }
        }
    }

    private val monitor = Any()
    private var active: Lease? = null
    private var generation = 1L

    fun tryAcquire(kind: Kind): Lease? =
        synchronized(monitor) {
            if (active != null) return@synchronized null
            Lease(this, kind, generation++).also { active = it }
        }

    fun activeKind(): Kind? = synchronized(monitor) { active?.kind }

    private fun release(lease: Lease, expectedGeneration: Long) {
        synchronized(monitor) {
            check(active === lease && lease.generation == expectedGeneration) {
                "Runtime work lease is stale"
            }
            active = null
        }
    }
}
