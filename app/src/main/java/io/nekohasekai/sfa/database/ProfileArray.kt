package io.nekohasekai.sfa.database

import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A top-level JSON array of complete configs (v2rayNG's multi custom-config export, or a
 * subscription serving one): libbox splits it, and every element becomes its own profile;
 * an array of Xray configs that differ only in one proxy outbound arrives already merged
 * into a single element, whose nodes form one group.
 * Naming and the element a subscription profile follows are decided by libbox
 * (core_profile_array.go, tested there); lists cross as JSON arrays of strings.
 */
internal object ProfileArray {
    /** One element after splitting: [remarks] is "" when the element has none. */
    data class Element(val remarks: String, val content: String, val core: String)

    /** A profile to create: the element's content under its profile name. */
    data class Entry(val name: String, val content: String, val core: String)

    private val strings = ListSerializer(String.serializer())

    /**
     * One entry per element, in order: named by its remarks or "<source> #<n>"; a lone element
     * without remarks (libbox merged an array of one-node Xray configs) takes the source's name.
     */
    fun entries(source: String, elements: List<Element>): List<Entry> {
        val names = Json.decodeFromString(strings, Libbox.profileArrayNames(source, Json.encodeToString(strings, elements.map { it.remarks })))
        return elements.zip(names) { element, name -> Entry(name, element.content, element.core) }
    }

    /**
     * Which element a subscription profile keeps following when its URL returns an array:
     * the saved content's remarks, then the profile's name, then the "<source> #n" fallback;
     * a one-element array always matches. Null when nothing matches.
     */
    fun select(remarks: List<String>, savedRemarks: String, profileName: String): Int? =
        Libbox.profileArraySelect(Json.encodeToString(strings, remarks), savedRemarks, profileName).takeIf { it >= 0 }
}
