package org.dmg.balletmaster.state

/**
 * Natural version comparator supporting semantic versioning, numeric components,
 * date-based versions, and alphanumeric segments.
 */
object VersionComparator : Comparator<String> {
    override fun compare(v1: String, v2: String): Int {
        val parts1 = v1.split(".", "-", "_")
        val parts2 = v2.split(".", "-", "_")
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrNull(i) ?: return -1
            val p2 = parts2.getOrNull(i) ?: return 1

            val n1 = p1.toIntOrNull()
            val n2 = p2.toIntOrNull()

            val cmp = if (n1 != null && n2 != null) {
                n1.compareTo(n2)
            } else {
                p1.compareTo(p2)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
