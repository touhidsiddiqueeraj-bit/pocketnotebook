package com.pocketnotebook.app.core.ingest

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Text extraction for the supported source types.
 * PDF → PDFBox-Android; DOCX → unzip + word/document.xml (XmlPullParser);
 * TXT/MD → as-is; HTML/URL → Jsoup with readability-style cleanup.
 */
object Extractors {

    private const val PDF_MAGIC = "%PDF"
    private const val ZIP_MAGIC = "PK"

    class ExtractException(message: String) : Exception(message)

    fun sniffType(fileName: String?, bytes: ByteArray? = null): String {
        val name = (fileName ?: "").lowercase()
        return when {
            name.endsWith(".pdf") -> "pdf"
            name.endsWith(".docx") -> "docx"
            name.endsWith(".md") || name.endsWith(".markdown") -> "md"
            name.endsWith(".txt") -> "txt"
            name.endsWith(".html") || name.endsWith(".htm") -> "html"
            bytes != null && bytes.size > 4 && String(bytes, 0, 4).startsWith(PDF_MAGIC) -> "pdf"
            bytes != null && bytes.size > 2 && String(bytes, 0, 2) == ZIP_MAGIC -> "docx"
            else -> "txt"
        }
    }

    // -------------------------------------------------------------------- PDF

    fun fromPdf(context: Context, bytes: ByteArray): String = try {
        com.tom_roush.pdfbox.PDFBoxResourceLoader.init(context)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.sortByPosition = true
            stripper.getText(doc)
        }
    } catch (t: Throwable) {
        throw ExtractException("PDF parse failed: ${t.message ?: t.javaClass.simpleName}")
    }

    // ------------------------------------------------------------------- DOCX

    fun fromDocx(bytes: ByteArray): String {
        val docXml = findZipEntry(bytes, "word/document.xml")
            ?: throw ExtractException("Not a DOCX (missing word/document.xml)")
        return docxXmlToText(docXml)
    }

    private fun findZipEntry(bytes: ByteArray, name: String): ByteArray? =
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var entry = zin.nextEntry
                val data = ByteArray(65536)
                while (entry != null) {
                    if (entry.name == name) {
                        val out = ByteArrayOutputStream()
                        var n: Int
                        while (zin.read(data).also { n = it } > 0) out.write(data, 0, n)
                        return out.toByteArray()
                    }
                    entry = zin.nextEntry
                }
                null
            }
        } catch (_: Throwable) {
            null
        }

    /** Visible text from word/document.xml: text runs + paragraph breaks. */
    internal fun docxXmlToText(docXml: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(docXml), null)

        val out = StringBuilder()
        var event = parser.eventType
        var inParagraph = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> inParagraph = true
                    "br", "tab" -> if (inParagraph) out.append(' ')
                }
                XmlPullParser.TEXT -> if (inParagraph) out.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "p" -> {
                        inParagraph = false
                        out.append("\n\n")
                    }
                }
            }
            event = parser.next()
        }
        return out.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    // -------------------------------------------------------------- HTML / URL

    fun fromHtml(html: String): String = try {
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("script,style,nav,footer,header,aside,form,noscript,iframe,svg").remove()
        val candidates = listOf("article", "main", "[role=main]", ".post-content", "#content", "body")
        var best: org.jsoup.nodes.Element? = null
        for (sel in candidates) {
            val el = doc.selectFirst(sel)
            if (el != null && el.text().length > (best?.text()?.length ?: 0)) best = el
        }
        best?.text() ?: ""
    } catch (t: Throwable) {
        throw ExtractException("HTML parse failed: ${t.message ?: t.javaClass.simpleName}")
    }

    fun fromUrl(url: String, timeoutMs: Int = 15000): Pair<String, String> = try {
        val doc = org.jsoup.Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 PocketNotebook/0.1")
            .timeout(timeoutMs)
            .followRedirects(true)
            .get()
        val title = doc.title().ifBlank { url }
        val body = fromHtml(doc.outerHtml())
        if (body.isBlank()) throw ExtractException("No readable content on that page")
        title to body
    } catch (t: ExtractException) {
        throw t
    } catch (t: Throwable) {
        throw ExtractException("Fetch failed: ${t.message ?: t.javaClass.simpleName}")
    }
}
