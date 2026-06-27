package com.luke.dilbert

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
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

    private fun infoJson(): String {
        val files = strips.listFiles() ?: emptyArray()
        return """{"count":${files.size},"bytes":${files.sumOf { it.length() }}}"""
    }

    inner class Bridge {
        @JavascriptInterface fun info(): String = infoJson()

        @JavascriptInterface fun clear() {
            strips.listFiles()?.forEach { it.delete() }
            done()
        }

        @JavascriptInterface fun cancel() { cancel = true }

        /**
         * "Download everything" — fetch the single ~1.4 GB .7z (one request, resumable)
         * then extract every strip into the cache. The progress bar runs 0–50% for the
         * download and 50–100% for extraction.
         */
        @JavascriptInterface fun downloadAll() {
            cancel = false
            thread {
                val part = File(filesDir, "archive.7z.part")
                val archive = File(filesDir, "archive.7z")
                try {
                    if (!archive.exists()) {
                        if (!downloadArchive(part)) { done(); return@thread }  // cancelled / failed
                        part.renameTo(archive)
                    }
                    extractArchive(archive)
                    archive.delete()
                } catch (e: Exception) {
                    // Leave archive.7z.part on disk so a re-tap resumes the download.
                }
                done()
            }
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
