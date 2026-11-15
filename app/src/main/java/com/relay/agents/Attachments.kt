package com.relay.agents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** صورة مصغّرة جاهزة للإرسال (JPEG base64). */
data class ImageData(val mime: String, val b64: String) {
    fun toJson() = JSONObject().put("mime", mime).put("b64", b64)

    companion object {
        fun fromJson(o: JSONObject) = ImageData(o.getString("mime"), o.getString("b64"))
    }
}

/** مرفق واحد بعد المعالجة: نص مستخرج و/أو صور و/أو ملاحظة وصفية. */
data class Attachment(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val text: String = "",
    val images: List<ImageData> = emptyList(),
    val note: String = ""
) {
    fun toJson() = JSONObject()
        .put("name", name).put("mime", mime).put("size", sizeBytes)
        .put("text", text).put("note", note)
        .put("images", JSONArray(images.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): Attachment {
            val ia = o.optJSONArray("images") ?: JSONArray()
            return Attachment(
                o.getString("name"), o.optString("mime"), o.optLong("size"),
                o.optString("text"), (0 until ia.length()).map { ImageData.fromJson(ia.getJSONObject(it)) },
                o.optString("note")
            )
        }
    }
}

/** مرفقات المهمة + وصف الصور الذي يولّده وكيل رؤية مرة واحدة ليستفيد منه كل الوكلاء. */
data class AttachmentBundle(
    val items: List<Attachment> = emptyList(),
    val imageDigest: String = ""
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val allImages: List<ImageData> get() = items.flatMap { it.images }.take(MAX_IMAGES)

    fun toJson() = JSONObject()
        .put("items", JSONArray(items.map { it.toJson() })).put("digest", imageDigest)

    /** نص يُحقن في تعليمات الوكلاء. */
    fun toContext(budget: Int = 20_000): String {
        if (items.isEmpty()) return ""
        var left = budget
        return buildString {
            append("Files provided by the user:\n")
            items.forEach { a ->
                append("- ${a.name} (${a.mime.ifEmpty { "?" }}, ${a.sizeBytes / 1024} KB)")
                if (a.note.isNotEmpty()) append(" — ${a.note}")
                append("\n")
            }
            items.filter { it.text.isNotEmpty() }.forEach { a ->
                if (left <= 0) return@forEach
                val chunk = a.text.take(minOf(left, 8_000))
                left -= chunk.length
                append("\n[Content of ${a.name}]\n$chunk\n")
            }
            if (imageDigest.isNotEmpty()) append("\n[Description of attached images/frames]\n${imageDigest.take(4_000)}\n")
        }
    }

    companion object {
        const val MAX_IMAGES = 8

        fun fromJson(o: JSONObject): AttachmentBundle {
            val a = o.optJSONArray("items") ?: JSONArray()
            return AttachmentBundle((0 until a.length()).map { Attachment.fromJson(a.getJSONObject(it)) }, o.optString("digest"))
        }
    }
}

/** تخزين المرفقات لكل مشروع: attachments/<id>.json */
class AttachmentStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
    private fun file(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    fun save(id: String, b: AttachmentBundle) {
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(b.toJson().toString())
        tmp.renameTo(file(id))
    }

    fun load(id: String): AttachmentBundle =
        runCatching { AttachmentBundle.fromJson(JSONObject(file(id).readText())) }.getOrDefault(AttachmentBundle())

    fun delete(id: String) {
        file(id).delete()
    }
}

/** يحوّل أي ملف يختاره المستخدم إلى نص/صور مناسبة للوكلاء. */
class AttachmentProcessor(private val ctx: Context) {

    fun process(uri: Uri): Attachment {
        val (name, size) = nameAndSize(uri)
        val mime = ctx.contentResolver.getType(uri).orEmpty().ifEmpty { guessMime(name) }
        val ext = name.substringAfterLast('.', "").lowercase()
        return runCatching {
            when {
                mime.startsWith("image/") -> imageAttachment(uri, name, mime, size)
                ext == "zip" || mime.contains("zip") -> zipAttachment(uri, name, mime, size)
                ext == "pdf" || mime == "application/pdf" -> pdfAttachment(uri, name, mime, size)
                mime.startsWith("video/") -> videoAttachment(uri, name, mime, size)
                mime.startsWith("text/") || ext in TEXT_EXT || mime in TEXT_MIMES -> textAttachment(uri, name, mime, size)
                else -> Attachment(name, mime, size, note = "Binary file: content not readable, only name and size are known")
            }
        }.getOrElse { Attachment(name, mime, size, note = "Could not process the file: ${it.message?.take(80)}") }
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        "mp4", "mov", "mkv", "webm", "3gp" -> "video/mp4"
        in TEXT_EXT -> "text/plain"
        else -> ""
    }

    // ---------- نص ----------
    private fun textAttachment(uri: Uri, name: String, mime: String, size: Long): Attachment {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_TEXT_BYTES) } ?: ByteArray(0)
        if (looksBinary(bytes)) return Attachment(name, mime, size, note = "Looks binary, not read as text")
        val truncated = size > MAX_TEXT_BYTES
        return Attachment(name, mime, size, text = String(bytes, Charsets.UTF_8),
            note = if (truncated) "Truncated at ${MAX_TEXT_BYTES / 1024} KB" else "")
    }

    // ---------- صورة ----------
    private fun imageAttachment(uri: Uri, name: String, mime: String, size: Long): Attachment {
        val bmp = decodeSampled(uri) ?: return Attachment(name, mime, size, note = "Could not decode the image")
        return Attachment(name, mime, size, images = listOf(toJpeg(bmp)), note = "Image ${bmp.width}×${bmp.height} (downscaled for analysis)")
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
    }

    private fun toJpeg(src: Bitmap): ImageData {
        val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        var q = 80
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            bytes = out.toByteArray()
            q -= 15
        } while (bytes.size > MAX_IMAGE_BYTES && q >= 35)
        return ImageData("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    // ---------- ZIP ----------
    private fun zipAttachment(uri: Uri, name: String, mime: String, size: Long): Attachment {
        val sb = StringBuilder()
        val others = mutableListOf<String>()
        val images = mutableListOf<ImageData>()
        var entries = 0
        var total = 0
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zin ->
                while (entries < MAX_ZIP_ENTRIES) {
                    val e = zin.nextEntry ?: break
                    entries++
                    if (e.isDirectory) continue
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in TEXT_EXT && total < MAX_ZIP_TEXT -> {
                            val b = readCapped(zin, MAX_ENTRY_BYTES)
                            if (!looksBinary(b)) {
                                sb.append("=== ${e.name} ===\n").append(String(b, Charsets.UTF_8)).append("\n\n")
                                total += b.size
                            } else others.add(e.name)
                        }
                        ext in IMAGE_EXT && images.size < 3 -> {
                            val b = readCapped(zin, 4 * 1024 * 1024)
                            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                            if (bmp != null) images.add(toJpeg(bmp)) else others.add(e.name)
                        }
                        else -> others.add(e.name)
                    }
                }
            }
        }
        val note = buildString {
            append("ZIP archive: $entries entries")
            if (images.isNotEmpty()) append(", ${images.size} images extracted")
            if (others.isNotEmpty()) append(". Unreadable files: ${others.take(25).joinToString(", ")}")
        }
        return Attachment(name, mime, size, text = sb.toString(), images = images, note = note)
    }

    // ---------- PDF ----------
    private fun pdfAttachment(uri: Uri, name: String, mime: String, size: Long): Attachment {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: return Attachment(name, mime, size, note = "Could not open the PDF")
        val images = mutableListOf<ImageData>()
        var pages = 0
        PdfRenderer(pfd).use { r ->
            pages = r.pageCount
            for (i in 0 until minOf(pages, MAX_PDF_PAGES)) {
                r.openPage(i).use { p ->
                    val w = 1000
                    val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    images.add(toJpeg(bmp))
                }
            }
        }
        return Attachment(name, mime, size, images = images,
            note = "PDF with $pages pages; first ${images.size} pages rendered as images for analysis (text not extracted)")
    }

    // ---------- فيديو ----------
    private fun videoAttachment(uri: Uri, name: String, mime: String, size: Long): Attachment {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val images = mutableListOf<ImageData>()
            if (ms > 0) {
                for (i in 0 until VIDEO_FRAMES) {
                    val t = ms * 1000L * (2 * i + 1) / (2 * VIDEO_FRAMES)
                    mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { images.add(toJpeg(it)) }
                }
            }
            return Attachment(name, mime, size, images = images,
                note = "Video ${ms / 1000}s ${w ?: "?"}×${h ?: "?"}; ${images.size} frames spread over its duration. Audio is not transcribed")
        } finally {
            runCatching { mmr.release() }
        }
    }

    // ---------- أدوات ----------
    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var left = cap
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun looksBinary(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        val sample = b.take(2048)
        return sample.count { it.toInt() == 0 } > 0
    }

    companion object {
        const val MAX_TEXT_BYTES = 100 * 1024
        const val MAX_ENTRY_BYTES = 60 * 1024
        const val MAX_ZIP_TEXT = 200 * 1024
        const val MAX_ZIP_ENTRIES = 300
        const val MAX_SIDE = 1280
        const val MAX_IMAGE_BYTES = 500 * 1024
        const val MAX_PDF_PAGES = 4
        const val VIDEO_FRAMES = 4

        val TEXT_EXT = setOf(
            "txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log", "html", "htm", "css", "js", "ts",
            "kt", "kts", "java", "py", "c", "h", "cpp", "cs", "go", "rs", "rb", "php", "sh", "sql", "gradle", "properties", "srt", "tex"
        )
        val TEXT_MIMES = setOf("application/json", "application/xml", "application/javascript", "application/x-sh")
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
