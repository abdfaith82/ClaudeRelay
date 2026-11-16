package com.relay.agents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** جلب الملفات والمجلدات من الهاتف (SAF) مع الملفات المخفية، وتصفية المسارات للرفع. */
object LocalFiles {
    const val MAX_FILE = 20L * 1024 * 1024
    const val MAX_TOTAL = 80L * 1024 * 1024
    const val MAX_COUNT = 5000
    private const val MAX_DEPTH = 30

    private val JUNK = setOf("build", "node_modules", ".gradle", "__pycache__", ".cxx")

    class Scan {
        val files = LinkedHashMap<String, ByteArray>()
        var skipped = 0
        var total = 0L
        var truncated = false

        fun add(path: String, bytes: ByteArray): Boolean {
            if (files.size >= MAX_COUNT || total + bytes.size > MAX_TOTAL) { truncated = true; return false }
            files[path] = bytes
            total += bytes.size
            return true
        }
    }

    /** مسار نسبي نظيف بشرطات مائلة، أو null إن كان فيه ".." أو فارغاً. */
    fun cleanPath(p: String): String? {
        val parts = p.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    fun isHidden(path: String): Boolean = path.split('/').any { it.startsWith(".") }

    /** يطبّق خيارات المخفي/المجلدات المؤقتة والمجلد الفرعي. مجلد .git مستبعد دائماً. */
    fun effective(
        staged: Map<String, ByteArray>, prefix: String, includeHidden: Boolean, skipJunk: Boolean
    ): Map<String, ByteArray> {
        val pre = cleanPath(prefix) ?: ""
        val out = LinkedHashMap<String, ByteArray>()
        for ((path, bytes) in staged) {
            val segs = path.split('/')
            if (".git" in segs) continue
            if (!includeHidden && isHidden(path)) continue
            if (skipJunk && segs.dropLast(1).any { it in JUNK }) continue
            out[if (pre.isEmpty()) path else "$pre/$path"] = bytes
        }
        return out
    }

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    private fun read(ctx: Context, uri: Uri): ByteArray? =
        runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    fun addFiles(ctx: Context, uris: List<Uri>, scan: Scan) {
        for (u in uris) {
            val name = cleanPath(displayName(ctx, u))
            val size = runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                }
            }.getOrNull() ?: -1L
            if (name == null || size > MAX_FILE) { scan.skipped++; continue }
            val b = read(ctx, u)
            if (b == null || b.size > MAX_FILE) { scan.skipped++; continue }
            if (!scan.add(name, b)) break
        }
    }

    fun addTree(ctx: Context, tree: Uri, scan: Scan) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        visit(ctx, tree, root, "", 0, scan)
    }

    private fun visit(ctx: Context, tree: Uri, docId: String, rel: String, depth: Int, scan: Scan) {
        if (depth > MAX_DEPTH || scan.truncated) return
        class Child(val id: String, val name: String, val dir: Boolean, val size: Long)
        val children = ArrayList<Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    children.add(
                        Child(
                            c.getString(0), c.getString(1) ?: "",
                            c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            if (c.isNull(3)) -1L else c.getLong(3)
                        )
                    )
                }
            }
        }
        for (ch in children) {
            if (scan.truncated) return
            val name = ch.name
            if (name.isEmpty() || name == "." || name == "..") continue
            val path = if (rel.isEmpty()) name else "$rel/$name"
            if (ch.dir) {
                if (name == ".git") continue
                visit(ctx, tree, ch.id, path, depth + 1, scan)
            } else {
                if (ch.size > MAX_FILE) { scan.skipped++; continue }
                val b = read(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, ch.id))
                if (b == null || b.size > MAX_FILE) scan.skipped++ else scan.add(path, b)
            }
        }
    }
}
