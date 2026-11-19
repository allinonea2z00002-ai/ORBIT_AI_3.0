package com.orbit.ai.productivity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

const val LOCAL_SEARCH_LABEL = "Search in document (offline)"

/** One thing the user can ask an assistant tool to do. */
data class ToolAction(val label: String, val instruction: String)

enum class ToolKind(val title: String, val implemented: Boolean, val note: String) {
    STUDY("Study", true, "Explain, summarise, quiz or translate. Paste text or a topic."),
    PDF("PDF", true, "Pick a PDF. Text is extracted on the phone; scanned PDFs with no text layer will not work."),
    DOCUMENT("Document", true, "Write, rewrite, translate or fix grammar."),
    CODE("Code", true, "Paste code or an error message."),
    IMAGE("Image", true, "Pick a photo. Needs online AI (Gemini or Claude provider)."),
    WEB("Web", false, "Web search with sources is not implemented yet. ORBIT's answers do not come from live web results.");

    val actions: List<ToolAction>
        get() = when (this) {
            STUDY -> listOf(
                ToolAction("Explain simply", "Explain the following topic simply and clearly, with one example."),
                ToolAction("Short notes", "Write concise revision notes (bullet points) for the following."),
                ToolAction("Exam answer", "Write a well-structured exam answer for the following question."),
                ToolAction("Summarise chapter", "Summarise the following text in a short, structured way."),
                ToolAction("Make a quiz", "Create 5 multiple-choice questions with answers for the following material."),
                ToolAction("Translate", "Translate the following between Bangla and English (detect the source language).")
            )
            PDF -> listOf(
                ToolAction("Summarise", "Summarise this document. Mention its main sections."),
                ToolAction("Ask a question", "Answer the user's question using only this document. If the answer is not in it, say so."),
                ToolAction("Explain passage", "Explain the following passage from the document in simple terms."),
                ToolAction(LOCAL_SEARCH_LABEL, "")
            )
            DOCUMENT -> listOf(
                ToolAction("Write", "Write a clear, well-structured document based on the following brief."),
                ToolAction("Summarise", "Summarise the following text."),
                ToolAction("Rewrite", "Rewrite the following text to be clearer and more professional. Keep the meaning."),
                ToolAction("Translate", "Translate the following between Bangla and English (detect the source language)."),
                ToolAction("Fix grammar", "Fix grammar and spelling in the following text. Return only the corrected text.")
            )
            CODE -> listOf(
                ToolAction("Explain code", "Explain what the following code does, step by step."),
                ToolAction("Generate code", "Write code for the following request. Include brief usage notes."),
                ToolAction("Debug error", "Find the likely cause of the following error or bug and show a fix."),
                ToolAction("Review", "Review the following code for bugs, security and readability problems."),
                ToolAction("Improve", "Suggest concrete improvements to the following code and show the improved version.")
            )
            IMAGE -> listOf(
                ToolAction("Describe", "Describe this image in detail."),
                ToolAction("Read text", "Extract all visible text from this image exactly as written."),
                ToolAction("Ask a question", "Answer the user's question about this image.")
            )
            WEB -> emptyList()
        }
}

/** Offline helpers for documents and images. */
class DocumentTools(private val context: Context) {

    private var pdfReady = false

    /** Extracts the text layer of a PDF. Returns an error for scanned/image-only PDFs. */
    suspend fun extractPdfText(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!pdfReady) { PDFBoxResourceLoader.init(context.applicationContext); pdfReady = true }
            context.contentResolver.openInputStream(uri)!!.use { input ->
                PDDocument.load(input).use { doc ->
                    val text = PDFTextStripper().getText(doc).trim()
                    if (text.isBlank()) error("This PDF has no extractable text (it may be a scan).")
                    text
                }
            }
        }
    }

    suspend fun readTextFile(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
    }

    /** Local, offline search inside extracted text. Returns up to [limit] matches with a little context. */
    fun searchText(text: String, query: String, limit: Int = 20): List<String> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var from = 0
        while (out.size < limit) {
            val i = text.indexOf(query, from, ignoreCase = true)
            if (i < 0) break
            val a = (i - 60).coerceAtLeast(0)
            val b = (i + query.length + 80).coerceAtMost(text.length)
            out += "…" + text.substring(a, b).replace('\n', ' ') + "…"
            from = i + query.length
        }
        return out
    }

    /** Shrinks a photo (longest side 1280 px, JPEG 85) so it uploads quickly. */
    suspend fun imageToBase64Jpeg(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Could not read the image.")
            val scale = 1280f / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    companion object {
        /** Keeps very long documents inside what a model can take. The UI tells the user when this happens. */
        fun clip(text: String, maxChars: Int): Pair<String, Boolean> =
            if (text.length <= maxChars) text to false else text.take(maxChars) to true
    }
}
