package io.nekohasekai.sfa.database

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/** One profile's metadata, kept in the client store ([ProfileManager]); its configuration is the file at `typed.path`. */
@Parcelize
class Profile(
    var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",
    var icon: String? = null,
    var typed: TypedProfile = TypedProfile(),
) : Parcelable {
    /** [userOrder] as read from the store; an update writes the order only when it changed since. */
    @IgnoredOnParcel internal var storedOrder: Long? = null

    /** The core the content resolved to and the file stamp it was resolved for (BoxProfiles). */
    @IgnoredOnParcel internal var resolvedCore: String = ""

    @IgnoredOnParcel internal var resolvedStamp: String = ""
}
