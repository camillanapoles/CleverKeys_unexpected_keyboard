package tribixbite.cleverkeys.stt.gemini

import android.util.Base64
import tribixbite.cleverkeys.stt.SpeechEngine
import tribixbite.cleverkeys.stt.SpeechEngineException
import tribixbite.cleverkeys.stt.SpeechHypothesis
import tribixbite.cleverkeys.stt.SpeechMemoryReport
import tribixbite.cleverkeys.stt.SpeechSession
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Gemini API speech-to-text engine implementing [SpeechEngine].
 *
 * Buffers 16 kHz mono float PCM in memory during [SpeechSession.accept] and sends a single
 * in-memory WAV payload to Gemini `generateContent` in [SpeechSession.finish].
 *
 * Privacy / Security:
 * - The API key is provided at runtime from local device SharedPreferences and sent only in the
 *   `x-goog-api-key` request header (never in URLs, never logged, never persisted to disk).
 * - Audio stays strictly in memory for the duration of the session.
 */
class GeminiSpeechEngine(
    private val apiKey: String,
    private val model: String,
    private val prompt: String,
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : SpeechEngine {

    override val maxSegmentSeconds: Int = 60

    override fun newSession(): SpeechSession = GeminiSpeechSession()

    override fun memoryReport(): SpeechMemoryReport =
        SpeechMemoryReport(modelBytes = 0L, loadMillis = 0L, detail = "gemini-api:$model")

    override fun close() = Unit

    private inner class GeminiSpeechSession : SpeechSession {
        private val pcmOut = ByteArrayOutputStream(sampleRate * 2 * 4)

        @Volatile
        override var isCancelled: Boolean = false
            private set

        override fun accept(pcm: FloatArray): SpeechHypothesis? {
            if (isCancelled || pcm.isEmpty()) return null
            val bytes = ByteArray(pcm.size * 2)
            var idx = 0
            for (sample in pcm) {
                val clamped = sample.coerceIn(-1f, 1f)
                val s = (clamped * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[idx++] = (s and 0xFF).toByte()
                bytes[idx++] = ((s ushr 8) and 0xFF).toByte()
            }
            pcmOut.write(bytes)
            return null
        }

        override fun finish(): SpeechHypothesis {
            if (isCancelled) return SpeechHypothesis("", 0, isFinal = true)
            val pcmBytes = pcmOut.toByteArray()
            // Ignore ultra-short taps (< 0.15s of 16kHz 16-bit mono audio = 4800 bytes)
            if (pcmBytes.size < MIN_AUDIO_BYTES) {
                return SpeechHypothesis("", 0, isFinal = true)
            }
            val wavBytes = GeminiProtocol.buildWavBytes(pcmBytes, sampleRate)
            val transcript = if (GeminiProtocol.isInteractionsModel(model)) {
                transcribeViaInteractions(apiKey, model, wavBytes)
            } else {
                val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val requestBody = GeminiProtocol.buildRequestBody(base64Wav, prompt)
                val responseJson = postGenerateContent(apiKey, model, requestBody)
                if (isCancelled) return SpeechHypothesis("", 0, isFinal = true)
                GeminiProtocol.parseGeminiTranscript(responseJson)
            }
            if (isCancelled) return SpeechHypothesis("", 0, isFinal = true)
            android.util.Log.i("VoiceInput", "Gemini STT: model=$model, pcmBytes=${pcmBytes.size}, chars=${transcript.length}")
            val words = SpeechHypothesis.splitWords(transcript)
            return SpeechHypothesis(transcript, words.size, isFinal = true)
        }

        override fun cancel() {
            isCancelled = true
        }

        override fun close() {
            pcmOut.reset()
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = GeminiProtocol.DEFAULT_SAMPLE_RATE
        private const val MIN_AUDIO_BYTES = 4_800
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_POLL_ATTEMPTS = 120

        private fun postGenerateContent(apiKey: String, model: String, body: String): String {
            val safeModel = model.trim().ifEmpty { "gemini-2.5-flash" }
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent")
            return executeRequest(
                url = endpoint,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/json; charset=UTF-8",
                    "x-goog-api-key" to apiKey,
                ),
                body = body.toByteArray(Charsets.UTF_8),
            ).body
        }

        private fun transcribeViaInteractions(apiKey: String, model: String, wavBytes: ByteArray): String {
            val uploadUrl = startResumableUpload(apiKey, wavBytes.size)
            val fileInfo = uploadAudioBytes(apiKey, uploadUrl, wavBytes)
            if (fileInfo.uri.isEmpty() || fileInfo.name.isEmpty()) {
                throw SpeechEngineException("Gemini Files API returned incomplete file metadata")
            }
            try {
                waitForFileActive(apiKey, fileInfo)
                val interactionBody = GeminiProtocol.buildInteractionBody(
                    model = model,
                    fileUri = fileInfo.uri,
                    smartMode = true,
                )
                val createResp = executeRequest(
                    url = URL("https://generativelanguage.googleapis.com/v1beta/interactions"),
                    method = "POST",
                    headers = mapOf(
                        "Content-Type" to "application/json; charset=UTF-8",
                        "x-goog-api-key" to apiKey,
                    ),
                    body = interactionBody.toByteArray(Charsets.UTF_8),
                ).body
                var status = GeminiProtocol.parseInteractionStatus(createResp)
                var attempts = 0
                while (status.status in INTERACTION_PENDING_STATES && attempts < MAX_POLL_ATTEMPTS) {
                    if (status.id.isEmpty()) break
                    Thread.sleep(POLL_INTERVAL_MS)
                    attempts++
                    val pollResp = executeRequest(
                        url = URL("https://generativelanguage.googleapis.com/v1beta/interactions/${status.id}"),
                        method = "GET",
                        headers = mapOf("x-goog-api-key" to apiKey),
                    ).body
                    status = GeminiProtocol.parseInteractionStatus(pollResp)
                }
                if (status.status in INTERACTION_FAILED_STATES) {
                    throw SpeechEngineException("Gemini interaction ${status.status}")
                }
                return status.transcript
            } finally {
                deleteFileBestEffort(apiKey, fileInfo.name)
            }
        }

        private fun startResumableUpload(apiKey: String, contentLength: Int): String {
            val resp = executeRequest(
                url = URL("https://generativelanguage.googleapis.com/upload/v1beta/files"),
                method = "POST",
                headers = mapOf(
                    "x-goog-api-key" to apiKey,
                    "X-Goog-Upload-Protocol" to "resumable",
                    "X-Goog-Upload-Command" to "start",
                    "X-Goog-Upload-Header-Content-Length" to contentLength.toString(),
                    "X-Goog-Upload-Header-Content-Type" to "audio/wav",
                    "Content-Type" to "application/json; charset=UTF-8",
                ),
                body = """{"file":{"display_name":"cleverkeys-dictation.wav"}}""".toByteArray(Charsets.UTF_8),
            )
            return resp.uploadUrl
                ?: throw SpeechEngineException("Missing upload URL from Gemini Files API")
        }

        private fun uploadAudioBytes(
            apiKey: String,
            uploadUrl: String,
            wavBytes: ByteArray,
        ): GeminiProtocol.UploadedFileInfo {
            val resp = executeRequest(
                url = URL(uploadUrl),
                method = "POST",
                headers = mapOf(
                    "x-goog-api-key" to apiKey,
                    "X-Goog-Upload-Offset" to "0",
                    "X-Goog-Upload-Command" to "upload, finalize",
                    "Content-Type" to "audio/wav",
                ),
                body = wavBytes,
            )
            return GeminiProtocol.parseUploadedFileInfo(resp.body)
        }

        private fun waitForFileActive(apiKey: String, initial: GeminiProtocol.UploadedFileInfo) {
            var current = initial
            var attempts = 0
            while (current.state.isNotEmpty() && current.state != "ACTIVE" && attempts < MAX_POLL_ATTEMPTS) {
                if (current.state == "FAILED") {
                    throw SpeechEngineException("Gemini uploaded audio file processing failed")
                }
                Thread.sleep(POLL_INTERVAL_MS)
                attempts++
                val pollResp = executeRequest(
                    url = URL("https://generativelanguage.googleapis.com/v1beta/${current.name}"),
                    method = "GET",
                    headers = mapOf("x-goog-api-key" to apiKey),
                ).body
                current = GeminiProtocol.parseUploadedFileInfo(pollResp)
            }
            if (current.state == "FAILED") {
                throw SpeechEngineException("Gemini uploaded audio file processing failed")
            }
        }

        private fun deleteFileBestEffort(apiKey: String, fileName: String) {
            try {
                val conn = (URL("https://generativelanguage.googleapis.com/v1beta/$fileName")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Best-effort cleanup only
            }
        }

        private data class HttpResponse(val body: String, val uploadUrl: String?)

        private fun executeRequest(
            url: URL,
            method: String,
            headers: Map<String, String>,
            body: ByteArray? = null,
        ): HttpResponse {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                if (body != null) {
                    doOutput = true
                }
            }
            return try {
                if (body != null) {
                    conn.outputStream.use { os -> os.write(body) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val snippet = text.replace('\n', ' ').take(200)
                    throw SpeechEngineException("Gemini API HTTP $code: $snippet")
                }
                val uploadUrl = conn.headerFields.entries
                    .firstOrNull { it.key?.equals("x-goog-upload-url", ignoreCase = true) == true }
                    ?.value
                    ?.firstOrNull()
                HttpResponse(body = text, uploadUrl = uploadUrl)
            } catch (e: SpeechEngineException) {
                throw e
            } catch (e: Exception) {
                throw SpeechEngineException("Gemini request failed: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }

        private val INTERACTION_PENDING_STATES = setOf("queued", "in_progress", "requires_action")
        private val INTERACTION_FAILED_STATES = setOf("failed", "cancelled", "incomplete")
    }
}
