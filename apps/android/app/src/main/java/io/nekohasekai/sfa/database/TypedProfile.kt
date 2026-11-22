package io.nekohasekai.sfa.database

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import androidx.room.TypeConverter
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.TypedProfile.Type.values
import io.nekohasekai.sfa.ktx.marshall
import io.nekohasekai.sfa.ktx.unmarshall
import java.util.Date

class TypedProfile() : Parcelable {
    enum class Type {
        Local,
        Remote,
        ;

        fun getString(context: Context): String = when (this) {
            Local -> context.getString(R.string.profile_type_local)
            Remote -> context.getString(R.string.profile_type_remote)
        }

        companion object {
            fun valueOf(value: Int): Type {
                for (it in values()) {
                    if (it.ordinal == value) {
                        return it
                    }
                }
                return Local
            }
        }
    }

    var path = ""
    var type = Type.Local
    var remoteURL: String = ""
    var lastUpdated: Date = Date(0)
    var autoUpdate: Boolean = false
    var autoUpdateInterval = DEFAULT_INTERVAL

    /** Core that runs this profile: "auto" (detect from content), "sing-box", "xray" or "mihomo". */
    var core: String = CORE_AUTO

    // Parcel v3: what the provider's subscription-userinfo reported at the last
    // successful fetch (bytes, Unix seconds; 0 = not reported / no expiry).
    var upload: Long = 0L
    var download: Long = 0L
    var total: Long = 0L
    var expire: Long = 0L

    /** profile-web-page-url from the last fetch, http/https only. */
    var webPageUrl: String = ""

    /** The user chose [autoUpdateInterval]; provider profile-update-interval hints no longer apply. */
    var intervalUserSet: Boolean = false

    constructor(reader: Parcel) : this() {
        readFields(ParcelFields(reader))
    }

    /** Reads every Parcel version; v1/v2 values are kept as they were written. */
    internal fun readFields(reader: FieldReader) {
        val version = reader.readInt()
        path = reader.readString() ?: ""
        type = Type.valueOf(reader.readInt())
        remoteURL = reader.readString() ?: ""
        autoUpdate = reader.readInt() == 1
        lastUpdated = Date(reader.readLong())
        if (version >= 1) {
            autoUpdateInterval = reader.readInt()
        }
        if (version >= 2) {
            core = reader.readString()?.takeIf { it in CORES } ?: CORE_AUTO
        }
        if (version >= 3) {
            upload = reader.readLong().coerceAtLeast(0L)
            download = reader.readLong().coerceAtLeast(0L)
            total = reader.readLong().coerceAtLeast(0L)
            expire = reader.readLong().coerceAtLeast(0L)
            webPageUrl = reader.readString() ?: ""
            intervalUserSet = reader.readInt() == 1
        } else {
            // Before provider hints existed every non-default interval was the user's choice.
            intervalUserSet = autoUpdateInterval != DEFAULT_INTERVAL
        }
    }

    internal fun writeFields(writer: FieldWriter) {
        writer.writeInt(PARCEL_VERSION)
        writer.writeString(path)
        writer.writeInt(type.ordinal)
        writer.writeString(remoteURL)
        writer.writeInt(if (autoUpdate) 1 else 0)
        writer.writeLong(lastUpdated.time)
        writer.writeInt(autoUpdateInterval)
        writer.writeString(core)
        writer.writeLong(upload)
        writer.writeLong(download)
        writer.writeLong(total)
        writer.writeLong(expire)
        writer.writeString(webPageUrl)
        writer.writeInt(if (intervalUserSet) 1 else 0)
    }

    override fun writeToParcel(writer: Parcel, flags: Int) {
        writeFields(ParcelFields(writer))
    }

    /** An independent copy of every field, for duplicating a profile or restoring a backup over it. */
    fun copy(): TypedProfile = TypedProfile().also {
        it.path = path
        it.type = type
        it.remoteURL = remoteURL
        it.lastUpdated = Date(lastUpdated.time)
        it.autoUpdate = autoUpdate
        it.autoUpdateInterval = autoUpdateInterval
        it.core = core
        it.upload = upload
        it.download = download
        it.total = total
        it.expire = expire
        it.webPageUrl = webPageUrl
        it.intervalUserSet = intervalUserSet
    }

    /** The Parcel calls TypedProfile uses, so the format can be checked without Android. */
    internal interface FieldReader {
        fun readInt(): Int
        fun readLong(): Long
        fun readString(): String?
    }

    internal interface FieldWriter {
        fun writeInt(value: Int)
        fun writeLong(value: Long)
        fun writeString(value: String?)
    }

    private class ParcelFields(private val parcel: Parcel) : FieldReader, FieldWriter {
        override fun readInt() = parcel.readInt()
        override fun readLong() = parcel.readLong()
        override fun readString() = parcel.readString()
        override fun writeInt(value: Int) = parcel.writeInt(value)
        override fun writeLong(value: Long) = parcel.writeLong(value)
        override fun writeString(value: String?) = parcel.writeString(value)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<TypedProfile> {
        const val CORE_AUTO = "auto"
        const val CORE_SING_BOX = "sing-box"
        const val CORE_XRAY = "xray"
        const val CORE_MIHOMO = "mihomo"
        val CORES = setOf(CORE_AUTO, CORE_SING_BOX, CORE_XRAY, CORE_MIHOMO)
        const val PARCEL_VERSION = 3
        const val DEFAULT_INTERVAL = 60

        override fun createFromParcel(parcel: Parcel): TypedProfile = TypedProfile(parcel)

        override fun newArray(size: Int): Array<TypedProfile?> = arrayOfNulls(size)
    }

    class Convertor {
        @TypeConverter
        fun marshall(profile: TypedProfile) = profile.marshall()

        @TypeConverter
        fun unmarshall(content: ByteArray) = content.unmarshall(::TypedProfile)
    }
}
