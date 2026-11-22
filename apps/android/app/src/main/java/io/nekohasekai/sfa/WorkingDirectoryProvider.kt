package io.nekohasekai.sfa

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class WorkingDirectoryProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "working_directory"
        private const val ROOT_DOC_ID = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }

    private val baseDir: File
        get() = context!!.getExternalFilesDir(null)!!.canonicalFile

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(DocumentsContract.Root.COLUMN_SUMMARY, context!!.getString(R.string.working_directory))
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId)
        includeFile(result, documentId, file)
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        parent.listFiles()?.forEach { file ->
            // A symlink leading out of the working directory is not listed.
            if (!isInside(baseDir, file)) return@forEach
            includeFile(result, getDocIdForFile(file), file)
        }
        return result
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = getFileForDocId(parentDocumentId)
        val file = resolveInside(File(parent, requireSafeName(displayName)))

        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            file.mkdirs()
        } else {
            file.createNewFile()
        }

        return getDocIdForFile(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = getFileForDocId(documentId)
        if (file == baseDir) throw FileNotFoundException("The root cannot be renamed")
        val newFile = resolveInside(File(file.parentFile, requireSafeName(displayName)))
        if (!file.renameTo(newFile)) throw FileNotFoundException("Rename failed")
        return getDocIdForFile(newFile)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { getFileForDocId(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { getFileForDocId(documentId) }.getOrNull() ?: return false
        return child != parent && isInside(parent, child)
    }

    /**
     * Resolves a document ID (a path relative to [baseDir]) to its file. An ID that
     * leads out of the working directory (`..`, an absolute path, a symlink out) is
     * refused rather than concatenated onto the base path.
     */
    private fun getFileForDocId(documentId: String): File {
        if (documentId == ROOT_DOC_ID) {
            return baseDir
        }
        return resolveInside(File(baseDir, documentId))
    }

    private fun resolveInside(file: File): File {
        val canonical = file.canonicalFile
        if (!isInside(baseDir, canonical)) {
            throw FileNotFoundException("Outside the working directory")
        }
        return canonical
    }

    /** Separator-aware: `/base` contains `/base/x` but not `/base-other/x`. */
    private fun isInside(parent: File, child: File): Boolean {
        val parentPath = parent.canonicalPath
        val childPath = child.canonicalPath
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }

    /** A display name is one path segment: no separators, no `.` or `..`, no NUL. */
    private fun requireSafeName(displayName: String): String {
        require(
            displayName.isNotEmpty() && displayName != "." && displayName != ".." &&
                '/' !in displayName && '\\' !in displayName && '\u0000' !in displayName,
        ) { "Invalid file name" }
        return displayName
    }

    private fun getDocIdForFile(file: File): String {
        val path = file.canonicalPath
        val basePath = baseDir.path

        return if (path == basePath) {
            ROOT_DOC_ID
        } else {
            path.removePrefix("$basePath/")
        }
    }

    private fun includeFile(result: MatrixCursor, documentId: String, file: File) {
        var flags = 0

        if (file.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }

        if (file.parentFile?.canWrite() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }

        val mimeType = if (file.isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            getMimeType(file)
        }

        result.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        if (extension.isNotEmpty()) {
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mimeType != null) {
                return mimeType
            }
        }
        return "application/octet-stream"
    }
}
