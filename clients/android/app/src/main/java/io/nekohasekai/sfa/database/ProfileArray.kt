package io.nekohasekai.sfa.database

import io.nekohasekai.sfa.fkcore.FkLib
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A top-level JSON array of complete configs (v2rayNG's multi custom-config export, or a
 * subscription serving one): libbox splits it, and every element becomes its own profile;
 * an array of Xray configs that differ only in one proxy outbound arrives already merged
 * into a single element, whose nodes form one group.
 * Naming (ProfileContent.prepareArray) and the element a subscription profile follows are
 * decided by libbox (core_profile_array.go, tested there); lists cross as JSON arrays of strings.
 */
internal object ProfileArray {
    /** One element after splitting: [remarks] is "" when the element has none. */
    data class Element(val remarks: String, val content: String, val core: String)

    /** A profile to create: the element's content under its profile name. */
    data class Entry(val name: String, val content: String, val core: String)

    private val strings = ListSerializer(String.serializer())

    /**
     * Which element a subscription profile keeps following when its URL returns an array:
     * the saved content's remarks, then the profile's name, then the "<source> #n" fallback;
     * a one-element array always matches. Null when nothing matches.
     */
    fun select(remarks: List<String>, savedRemarks: String, profileName: String): Int? =
        FkLib.profileArraySelect(Json.encodeToString(strings, remarks), savedRemarks, profileName).takeIf { it >= 0 }
}
