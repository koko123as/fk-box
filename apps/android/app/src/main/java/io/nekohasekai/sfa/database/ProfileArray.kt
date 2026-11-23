package io.nekohasekai.sfa.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.put

/**
 * A top-level JSON array of complete configs (v2rayNG's multi custom-config export, or a
 * subscription serving one): libbox splits it ([ProfileContent.splitArray]), and every
 * element becomes its own profile. Naming the profiles, and which element a subscription
 * profile keeps following, are decided in client-core (crates/client-core/src/profile_array.rs).
 */
internal object ProfileArray {
    /** One element after splitting: [remarks] is "" when the element has none. */
    @Serializable
    data class Element(val remarks: String, val content: String, val core: String)

    /** A profile to create: the element's content under its profile name. */
    @Serializable
    data class Entry(val name: String, val content: String, val core: String)

    /**
     * Each element under its remarks, or "<source> #<n>" when it has none; a lone element
     * without remarks takes the source's own name.
     */
    fun entries(source: String, elements: List<Element>): List<Entry> = CoreStore.decode(
        ListSerializer(Entry.serializer()),
        CoreStore.call("profileArray.entries") {
            put("source", source)
            put("elements", CoreStore.encode(ListSerializer(Element.serializer()), elements))
        },
    )
}
