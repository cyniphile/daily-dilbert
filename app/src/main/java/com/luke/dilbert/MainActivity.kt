package com.luke.dilbert

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    @Volatile private var cancel = false
    @Volatile private var downloading = false
    private val archiveFiles = 12384   // strips in the archive
    private val strips: File by lazy { File(filesDir, "strips").apply { mkdirs() } }

    // Path-into-archive marker. Every strip URL is
    //   …/Dilbert_1989-2023_complete.7z/1989%2F<encoded-name>
    // and the part after this marker, percent-decoded, is exactly the 7z entry name
    // (e.g. "1989/1989-04-17_dating_ice cream_relationships.gif"). We cache by SHA of
    // that decoded path so a streamed strip and an extracted strip share one key.
    private val marker = "Dilbert_1989-2023_complete.7z/"
    private val archiveUrl =
        "https://archive.org/download/dilbert-1989-2023-complete.-7z_202303/Dilbert_1989-2023_complete.7z"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // persistent localStorage
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? {
                    // 1) bundled web app
                    assets.shouldInterceptRequest(request.url)?.let { return it }
                    // 2) a locally-saved strip, if we have it
                    val rel = relOf(request.url.toString())
                    if (rel != null) {
                        val f = File(strips, sha(rel))
                        if (f.exists()) {
                            val mime = if (rel.endsWith(".jpg") || rel.endsWith(".jpeg"))
                                "image/jpeg" else "image/gif"
                            return WebResourceResponse(mime, null, f.inputStream())
                        }
                    }
                    return null  // 3) network
                }
            }
            addJavascriptInterface(Bridge(), "DilbertNative")
        }

        // All strips saved: remove an archive left over from a download that was not necessary.
        thread { if (isComplete()) deleteArchive() }

        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.ddOnBack ? window.ddOnBack() : false") { r ->
            if (r != "true") finish()
        }
    }

    /** The decoded archive-relative path for a strip URL, or null if it isn't one. */
    private fun relOf(url: String): String? {
        val i = url.indexOf(marker)
        if (i < 0) return null
        val enc = url.substring(i + marker.length)
        if (enc.isEmpty()) return null
        return URLDecoder.decode(enc, "UTF-8")
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun callJs(js: String) = web.post { web.evaluateJavascript(js, null) }
    private fun progress(step: Int) = callJs("window.ddProgress && window.ddProgress($step,1000)")
    private fun done() = callJs("window.ddDone && window.ddDone()")

    /** Saved strips only: a ".part" file is an extraction that did not finish. */
    private fun savedStrips(): List<File> =
        (strips.listFiles() ?: emptyArray()).filter { !it.name.endsWith(".part") }

    /** True when every strip of the archive is on disk, so there is nothing left to download. */
    private fun isComplete(): Boolean = savedStrips().size >= archiveFiles

    private fun deleteArchive() {
        File(filesDir, "archive.7z").delete()
        File(filesDir, "archive.7z.part").delete()
    }

    private fun infoJson(): String {
        val files = savedStrips()
        return """{"count":${files.size},"bytes":${files.sumOf { it.length() }},""" +
            """"complete":${files.size >= archiveFiles},"busy":$downloading}"""
    }

    inner class Bridge {
        @JavascriptInterface fun info(): String = infoJson()

        @JavascriptInterface fun clear() {
            if (downloading) return
            strips.listFiles()?.forEach { it.delete() }
            deleteArchive()
            done()
        }

        @JavascriptInterface fun cancel() { cancel = true }

        /** Puts the strip at [url] on the clipboard as an image; answers via ddCopied(ok). */
        @JavascriptInterface fun copyImage(url: String) {
            thread {
                val ok = try { copyToClipboard(url); true } catch (e: Exception) { false }
                callJs("window.ddCopied && window.ddCopied($ok)")
            }
        }

        /**
         * "Download everything" — fetch the single ~1.4 GB .7z (one request, resumable)
         * then extract every strip into the cache. The progress bar runs 0–50% for the
         * download and 50–100% for extraction.
         */
        @JavascriptInterface fun downloadAll() {
            // Everything is saved already: do not fetch the 1.4 GB archive again.
            if (isComplete()) { deleteArchive(); done(); return }
            synchronized(this@MainActivity) {
                if (downloading) return      // one download at a time
                downloading = true
            }
            cancel = false
            thread {
                val part = File(filesDir, "archive.7z.part")
                val archive = File(filesDir, "archive.7z")
                try {
                    if (!archive.exists()) {
                        if (!downloadArchive(part)) return@thread  // cancelled
                        part.renameTo(archive)
                    }
                    extractArchive(archive)
                    if (!cancel) archive.delete()
                } catch (e: Exception) {
                    // Leave archive.7z.part on disk so a re-tap resumes the download.
                } finally {
                    downloading = false
                    done()
                }
            }
        }
    }

    /**
     * Copies one strip to the clipboard as a PNG (lossless, and the image type that the
     * most apps accept on paste). Uses the saved strip if there is one, else the network.
     */
    private fun copyToClipboard(url: String) {
        val rel = relOf(url) ?: throw IllegalArgumentException("not a strip URL")
        val saved = File(strips, sha(rel))
        val bytes = if (saved.exists()) saved.readBytes() else {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("not an image")
        val dir = File(cacheDir, "copy").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }          // keep only the newest copy
        val name = rel.substringAfterLast('/').take(10)   // "1989-04-17"
        val out = File(dir, "dilbert-$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
        val clip = ClipData.newUri(contentResolver, "Dilbert $name", uri)
        runOnUiThread {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }
    }

    /** Streams the archive to [part], resuming via HTTP Range if a partial exists. */
    private fun downloadArchive(part: File): Boolean {
        var from = if (part.exists()) part.length() else 0L
        val conn = (URL(archiveUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        val code = conn.responseCode
        if (from > 0 && code != HttpURLConnection.HTTP_PARTIAL) {  // server ignored Range
            from = 0; part.delete()
        }
        val total = conn.contentLengthLong.let { if (it > 0) it + from else -1L }
        conn.inputStream.use { input ->
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(from)
                val buf = ByteArray(1 shl 16)
                var got = from
                var lastPct = -1
                while (true) {
                    if (cancel) return false
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    got += n
                    if (total > 0) {
                        val pct = (got * 500 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
        }
        return !cancel
    }

    /** Extracts every file entry into the cache, keyed by its (decoded) path. */
    private fun extractArchive(archive: File) {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            val total = 12384.0   // file count in this archive; progress estimate only
            var doneN = 0
            var lastPct = -1
            var entry = sz.nextEntry
            while (entry != null) {
                if (cancel) break
                if (!entry.isDirectory) {
                    val f = File(strips, sha(entry.name))
                    if (!f.exists()) {
                        // Write to a temp then atomically rename, so a crash/kill never
                        // leaves a truncated strip that f.exists() would later skip.
                        val tmp = File(strips, sha(entry.name) + ".part")
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = sz.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        tmp.renameTo(f)
                    }
                    doneN++
                    val pct = 500 + (doneN / total * 500).toInt()
                    if (pct != lastPct) { lastPct = pct; progress(pct) }
                }
                entry = sz.nextEntry
            }
        }
    }
}
