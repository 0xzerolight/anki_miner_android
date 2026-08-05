package com.ankiminer.android.data.update

internal object VersionCompare {
    /**
     * Compares bounded dotted-decimal release versions.
     *
     * Invalid or excessive input fails closed: a false "newer" result would tell every user to
     * install a release that does not exist. Missing trailing components compare as zero.
     */
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val candidateParts = parse(candidate) ?: return false
        val currentParts = parse(current) ?: return false
        repeat(maxOf(candidateParts.size, currentParts.size)) { index ->
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private fun parse(value: String): List<Int>? {
        if (value.isEmpty() || value.length > MAX_VERSION_CHARS) return null
        val components = value.split('.')
        if (components.size > MAX_COMPONENTS) return null
        return components.map { component ->
            if (
                component.isEmpty() ||
                component.length > MAX_COMPONENT_DIGITS ||
                component.any { it !in '0'..'9' }
            ) {
                return null
            }
            component.toInt()
        }
    }

    private const val MAX_COMPONENTS = 8
    private const val MAX_COMPONENT_DIGITS = 9
    private const val MAX_VERSION_CHARS = MAX_COMPONENTS * MAX_COMPONENT_DIGITS + MAX_COMPONENTS - 1
}
