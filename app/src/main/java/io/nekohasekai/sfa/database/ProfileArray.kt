package io.nekohasekai.sfa.database

/**
 * A top-level JSON array of complete configs (v2rayNG's multi custom-config export, or a
 * subscription serving one): libbox splits it, and every element becomes its own profile.
 * Naming and the element a subscription profile follows are decided here, without libbox,
 * so they can be tested on the JVM.
 */
internal object ProfileArray {
    /** One element after splitting: [remarks] is "" when the element has none. */
    data class Element(val remarks: String, val content: String, val core: String)

    /** A profile to create: the element's content under its profile name. */
    data class Entry(val name: String, val content: String, val core: String)

    private val fallbackIndex = Regex("""\s#(\d+)$""")

    /** The element's remarks, or "<source> #<n>" (1-based) when it has none. */
    fun name(source: String, remarks: String, index: Int): String {
        val trimmed = remarks.trim()
        if (trimmed.isNotEmpty()) return trimmed
        val base = source.trim().ifEmpty { "Profile" }
        return "$base #${index + 1}"
    }

    fun entries(source: String, elements: List<Element>): List<Entry> =
        elements.mapIndexed { index, element -> Entry(name(source, element.remarks, index), element.content, element.core) }

    /**
     * Which element a subscription profile keeps following when its URL returns an array:
     * the element whose remarks equal the saved content's remarks, then the profile's name;
     * a profile named by the "<source> #n" fallback follows element n when that element has
     * no remarks; a one-element array always matches. Null when nothing matches.
     */
    fun select(remarks: List<String>, savedRemarks: String, profileName: String): Int? {
        val trimmed = remarks.map { it.trim() }
        savedRemarks.trim().takeIf { it.isNotEmpty() }?.let { saved ->
            trimmed.indexOf(saved).takeIf { it >= 0 }?.let { return it }
        }
        profileName.trim().takeIf { it.isNotEmpty() }?.let { name ->
            trimmed.indexOf(name).takeIf { it >= 0 }?.let { return it }
            fallbackIndex.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { number ->
                val index = number - 1
                if (index in trimmed.indices && trimmed[index].isEmpty()) return index
            }
        }
        return if (remarks.size == 1) 0 else null
    }
}
