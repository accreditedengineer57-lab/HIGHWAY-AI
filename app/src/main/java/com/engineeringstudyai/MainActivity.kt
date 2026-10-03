package com.engineeringstudyai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("engineering_ai", MODE_PRIVATE) }
    private val base = "https://api.openai.com/v1"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }
        web.addJavascriptInterface(AndroidBridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("pdf_uri", uri.toString()).remove("pdf_path").remove("vector_store").apply()
            callback("onProgress", "PDF selected. Tap Prepare AI Notes to index it.")
        }
    }

    private fun key(): String = prefs.getString("api_key", "") ?: ""
    private fun vectorStore(): String = prefs.getString("vector_store", "") ?: ""
    private fun pdfUri(): String = prefs.getString("pdf_uri", "") ?: ""
    private fun pdfPath(): String = prefs.getString("pdf_path", "") ?: ""

    private fun saveKey(k: String) { prefs.edit().putString("api_key", k.trim()).apply() }
    private fun saveVector(v: String) { prefs.edit().putString("vector_store", v).apply() }

    inner class AndroidBridge {
        @JavascriptInterface fun saveApiKey(k: String) { saveKey(k) }
        @JavascriptInterface fun hasApiKey(): Boolean = key().isNotBlank()
        @JavascriptInterface fun status() {
            val k = key()
            val vs = vectorStore()
            val pu = pdfUri()
            callback("onStatus", JSONObject().put("configured", k.isNotBlank()).put("ready", vs.isNotBlank()).put("pdf", pu.isNotBlank()).put("bundled", pdfPath().isNotBlank()).toString())
        }

        @JavascriptInterface fun useBundledPdf() {
            try {
                val assetName = "Design of Highway Pavements.pdf"
                val outFile = File(cacheDir, assetName)
                assets.open(assetName).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", outFile)
                prefs.edit().putString("pdf_path", outFile.absolutePath).putString("pdf_uri", uri.toString()).remove("vector_store").apply()
                callback("onProgress", "Bundled PDF selected. Tap Prepare AI Notes to index it.")
            } catch (e: Exception) {
                callback("onProgress", "Could not load bundled PDF: ${cleanError(e)}")
            }
        }

        @JavascriptInterface fun pickPdf() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 42)
        }
        @JavascriptInterface fun setup() {
            executor.execute {
                try {
                    val k = key(); if (k.isBlank()) throw Exception("Enter your OpenAI API key first.")
                    callback("onProgress", "Creating your private study index…")
                    val vs = createVectorStore(k)
                    callback("onProgress", "Uploading your selected Highway Engineering PDF…")
                    val fileId = uploadPdf(k)
                    callback("onProgress", "Adding the PDF to the AI search index…")
                    attachAndWait(k, vs, fileId)
                    saveVector(vs)
                    callback("onSetup", JSONObject().put("ok", true).put("vector_store_id", vs).toString())
                } catch (e: Exception) {
                    callback("onSetup", JSONObject().put("ok", false).put("error", cleanError(e)).toString())
                }
            }
        }
        @JavascriptInterface fun ask(question: String, mode: String, historyJson: String) {
            executor.execute {
                try {
                    val k = key(); if (k.isBlank()) throw Exception("Enter your OpenAI API key first.")
                    val vs = vectorStore(); if (vs.isBlank()) throw Exception("Prepare the PDF first using “Prepare AI Notes”.")
                    callback("onProgress", "Searching your notes and preparing the answer…")
                    val text = askOpenAI(k, vs, question, mode, historyJson)
                    callback("onAnswer", JSONObject().put("ok", true).put("text", text).toString())
                } catch (e: Exception) {
                    callback("onAnswer", JSONObject().put("ok", false).put("error", cleanError(e)).toString())
                }
            }
        }
        @JavascriptInterface fun openPdf() {
            if (pdfUri().isBlank() && pdfPath().isBlank()) { pickPdf(); return }
            try {
                val uri = if (pdfPath().isNotBlank()) {
                    androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", File(pdfPath()))
                } else Uri.parse(pdfUri())
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                callback("onProgress", "No PDF viewer was found. You can still prepare the AI notes.")
            }
        }
    }

    private fun createVectorStore(k: String): String {
        val body = JSONObject().put("name", "Engineering Study AI - Highway Construction").toString()
        val j = requestJson("POST", "$base/vector_stores", k, body)
        return j.getString("id")
    }

    private fun uploadPdf(k: String): String {
        if (pdfUri().isBlank()) throw Exception("Select your Highway Engineering PDF first.")
        val boundary = "----EngineeringStudyAI${UUID.randomUUID()}"
        val conn = URL("$base/files").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $k")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"purpose\"\r\n\r\nassistants\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"Highway construction Engineering.pdf\"\r\n")
            out.writeBytes("Content-Type: application/pdf\r\n\r\n")
            val input = if (pdfPath().isNotBlank()) FileInputStream(File(pdfPath())) else contentResolver.openInputStream(Uri.parse(pdfUri()))
            input?.use { stream ->
                val buf = ByteArray(1024 * 64)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            } ?: throw Exception("Unable to read the selected PDF.")
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val response = readResponse(conn, code)
        if (code !in 200..299) throw Exception(apiError(response, code))
        return JSONObject(response).getString("id")
    }

    private fun attachAndWait(k: String, vs: String, fileId: String) {
        val body = JSONObject().put("file_id", fileId).toString()
        val j = requestJson("POST", "$base/vector_stores/$vs/files", k, body)
        val vsFile = j.getString("id")
        repeat(90) {
            Thread.sleep(2000)
            val s = requestJson("GET", "$base/vector_stores/$vs/files/$vsFile", k, null)
            when (s.optString("status")) {
                "completed" -> return
                "failed", "cancelled" -> throw Exception(s.optJSONObject("last_error")?.optString("message") ?: "PDF indexing failed.")
            }
        }
        throw Exception("PDF indexing timed out. Please try Prepare AI Notes again.")
    }

    private fun askOpenAI(k: String, vs: String, question: String, mode: String, historyJson: String): String {
        val input = JSONArray()
        val history = try { JSONArray(historyJson) } catch (_: Exception) { JSONArray() }
        val start = maxOf(0, history.length() - 8)
        for (i in start until history.length()) {
            val h = history.getJSONObject(i)
            input.put(JSONObject().put("role", h.optString("role", "user")).put("content", h.optString("content", "")))
        }
        input.put(JSONObject().put("role", "user").put("content", question))
        val instructions = """
You are Engineering Study AI, a civil-engineering study tutor. The user's uploaded PDF is the primary source. Use file search before answering. Preserve the source's terminology, organization and level of detail. Do not invent a fact and do not silently replace the notes with outside information. If the uploaded notes do not support a requested detail, say so clearly and label any general engineering knowledge as such. Mode: $mode. For calculations show assumptions, formula, units, substitution and final answer. For exam answers use a clear exam-ready structure. For quizzes, base questions on retrieved material and put answers at the end. Mention that the answer is based on the uploaded Highway Construction Engineering PDF when appropriate.
""".trimIndent()
        val tools = JSONArray().put(JSONObject().put("type", "file_search").put("vector_store_ids", JSONArray().put(vs)))
        val body = JSONObject().put("model", "gpt-5.6-luna").put("instructions", instructions).put("input", input).put("tools", tools)
        val j = requestJson("POST", "$base/responses", k, body.toString())
        val outText = j.optString("output_text", "")
        if (outText.isNotBlank()) return outText
        val out = j.optJSONArray("output") ?: throw Exception("The AI returned no answer.")
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (x in 0 until content.length()) {
                val c = content.optJSONObject(x) ?: continue
                if (c.optString("type") == "output_text") sb.append(c.optString("text"))
            }
        }
        if (sb.isEmpty()) throw Exception("The AI returned no readable answer.")
        return sb.toString()
    }

    private fun requestJson(method: String, url: String, k: String, body: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $k")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val response = readResponse(conn, code)
        if (code !in 200..299) throw Exception(apiError(response, code))
        return JSONObject(response)
    }

    private fun readResponse(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun apiError(s: String, code: Int): String {
        return try { JSONObject(s).optJSONObject("error")?.optString("message") ?: "OpenAI request failed (HTTP $code)." } catch (_: Exception) { "OpenAI request failed (HTTP $code)." }
    }
    private fun cleanError(e: Exception): String = e.message ?: "Something went wrong."

    private fun callback(fn: String, value: String) {
        runOnUiThread { web.evaluateJavascript("window.$fn(${JSONObject.quote(value)});", null) }
    }
}
