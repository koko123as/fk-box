package io.nekohasekai.sfa.database

import android.util.AtomicFile
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** Serializes foreground edits with the background subscription updater. */
internal object ProfileContent {
    val mutex = Mutex()
    private val writeGeneration = AtomicLong()
    // File timestamps/length alone can miss equal-size writes and recovery within one clock tick.
    val generation: Long get() = writeGeneration.get()

    fun revision(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Content ready to save, with the core that will run it. */
    data class Prepared(val core: String, val content: String)

    /**
     * Turns what a user imported or a subscription returned into a profile for [declaredCore]
     * ("auto" detects): share links / Base64 node lists are converted, sing-box JSON drops the
     * retired capture options, and every result is checked by its own core's parser.
     */
    fun prepare(declaredCore: String, content: String): Prepared {
        require(content.toByteArray().size <= 16 * 1024 * 1024) { "Configuration exceeds 16 MiB" }
        val prepared = if (Libbox.detectCoreConfig(content) == Libbox.CoreLinks) {
            val converted = Libbox.convertCoreShareLinksFor(declaredCore, content)
            Prepared(converted.core, converted.content)
        } else {
            val core = Libbox.resolveProfileCore(declaredCore, content)
            Prepared(core, if (core == TypedProfile.CORE_SING_BOX) Libbox.removeUIRetiredCaptureOptions(content) else content)
        }
        validate(prepared.core, prepared.content)
        return prepared
    }

    fun validate(core: String, content: String) {
        require(content.toByteArray().size <= 16 * 1024 * 1024) { "Configuration exceeds 16 MiB" }
        Libbox.checkCoreConfig(core, content)
    }

    /** sing-box-only callers (LAN proxy toggle, backups of sing-box profiles). */
    fun validate(content: String) = validate(Libbox.resolveProfileCore(TypedProfile.CORE_AUTO, content), content)

    /** The core that runs [profile] with its saved [content]. */
    fun coreOf(profile: Profile, content: String): String = Libbox.resolveProfileCore(profile.typed.core, content)

    /** Caller holds [mutex] until the save, service application and any recovery finish. */
    suspend fun replaceLocked(
        profile: Profile,
        content: String,
        expectedRevision: String? = null,
        updatedAt: Date? = null,
        applySelected: suspend (recover: Boolean) -> Unit,
    ) {
        val file = File(profile.typed.path)
        val previous = if (file.exists()) file.readText() else null
        if (expectedRevision != null) {
            require(previous != null && revision(previous) == expectedRevision) { "Configuration changed; reload it before saving" }
        }
        val cleaned = prepare(profile.typed.core, content).content
        val changed = previous != cleaned
        if (!changed && updatedAt == null) return
        val savedAt = updatedAt ?: if (changed && profile.typed.type == TypedProfile.Type.Local) Date() else null

        val apply = changed && profile.id == Settings.selectedProfile
        val previousUpdatedAt = Date(profile.typed.lastUpdated.time)
        val previousMode = Settings.serviceMode
        val restore = mutableListOf<suspend () -> Unit>()
        if (changed) restore.add {
            if (previous != null) write(file, previous)
            else check(!file.exists() || file.delete()) { "Unable to restore profile file" }
        }
        if (savedAt != null) restore.add {
            profile.typed.lastUpdated = previousUpdatedAt
            check(ProfileManager.update(profile) == 1) { "Profile no longer exists" }
        }
        if (apply) restore.add {
            if (Settings.serviceMode != previousMode) Settings.serviceMode = previousMode
            Settings.dataStore.flush()
        }
        ProfileChange.apply(
            save = {
                if (changed) write(file, cleaned)
                if (savedAt != null) {
                    profile.typed.lastUpdated = savedAt
                    check(ProfileManager.update(profile) == 1) { "Profile no longer exists" }
                }
            },
            restore = restore,
            applyService = { recover -> if (apply) applySelected(recover) },
        )
    }

    fun write(file: File, content: String) = write(file) { it.write(content.toByteArray()) }

    fun write(file: File, writeContent: (OutputStream) -> Unit) {
        val atomic = AtomicFile(file)
        try {
            val stream = atomic.startWrite()
            try {
                writeContent(stream)
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        } finally {
            writeGeneration.incrementAndGet()
        }
    }
}
