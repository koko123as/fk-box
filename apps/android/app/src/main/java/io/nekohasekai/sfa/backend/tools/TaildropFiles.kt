package io.nekohasekai.sfa.backend.tools

import android.webkit.MimeTypeMap
import io.nekohasekai.libbox.TaildropDownloadHandler
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object TaildropFiles {
    private const val DIRECTORY_NAME = "Taildrop"
    private const val CACHE_DIRECTORY = "taildrop"
    private const val CACHE_LIFETIME = 24L * 60 * 60 * 1000

    fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /**
     * A received file's name as a single local path component. The name comes
     * from the sending peer: path separators, control characters and the "."
     * and ".." components must not reach a local path, a document title or a
     * share; whatever is left that is empty becomes "file".
     */
    fun safeName(name: String): String {
        val cleaned = name.map { c -> if (c == '/' || c == '\\' || c.isISOControl()) '_' else c }
            .joinToString("")
            .trim()
            .trimStart('.')
        return cleaned.ifEmpty { "file" }.take(255)
    }

    /** [name] resolved inside [directory], or null when it would leave it. */
    private fun inside(directory: File, name: String): File? {
        val file = File(directory, name)
        val base = directory.canonicalFile
        return file.takeIf { it.canonicalFile.parentFile == base }
    }

    private fun daemonFile(name: String): File? {
        if (CommandTarget.isRemote) return null
        // Only a name that is already a plain file name is looked up on disk.
        if (safeName(name) != name) return null
        val workingDirectory = Application.application.getExternalFilesDir(null) ?: return null
        val file = inside(File(workingDirectory, DIRECTORY_NAME), name) ?: return null
        return if (file.isFile) file else null
    }

    /**
     * The received file [name] as a local file: the daemon's own copy when it
     * is on this device, otherwise downloaded into the cache under [safeName].
     * The core is asked for the file by its original name.
     */
    suspend fun materialize(endpointTag: String, name: String): File = withContext(Dispatchers.IO) {
        daemonFile(name)?.let { return@withContext it }
        val cacheDirectory = File(Application.application.cacheDir, CACHE_DIRECTORY).also { it.mkdirs() }
        val destination = inside(cacheDirectory, safeName(name)) ?: throw IOException("Invalid file name")
        val finish = CompletableDeferred<String>()
        val session = CommandTarget.standaloneClient().downloadTaildropFile(
            endpointTag,
            name,
            destination.path,
            object : TaildropDownloadHandler {
                override fun onProgress(downloaded: Long, total: Long) {
                }

                override fun onFinish(errorMessage: String) {
                    finish.complete(errorMessage)
                }
            },
        )
        try {
            val errorMessage = finish.await()
            if (errorMessage.isNotEmpty()) {
                throw IOException(errorMessage)
            }
        } finally {
            session.close()
        }
        destination
    }

    fun cleanCache() {
        val directory = File(Application.application.cacheDir, CACHE_DIRECTORY)
        val expiry = System.currentTimeMillis() - CACHE_LIFETIME
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < expiry) {
                file.delete()
            }
        }
    }
}
