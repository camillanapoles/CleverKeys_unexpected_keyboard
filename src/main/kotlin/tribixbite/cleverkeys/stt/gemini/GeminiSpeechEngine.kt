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
            val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val requestBody = GeminiProtocol.buildRequestBody(base64Wav, prompt)
            val responseJson = postGenerateContent(apiKey, model, requestBody)
            if (isCancelled) return SpeechHypothesis("", 0, isFinal = true)
            val transcript = GeminiProtocol.parseGeminiTranscript(responseJson)
            val words = SpeechHypothesis.splitWords(transcript)
            return SpeechHypothesis(transcript, words.size, isFinal = true)
        }

        override fun cancel() {
            isCancelled = true
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = GeminiProtocol.DEFAULT_SAMPLE_RATE
        private const val MIN_AUDIO_BYTES = 4_800
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        private fun postGenerateContent(apiKey: String, model: String, body: String): String {
            val safeModel = model.trim().ifEmpty { "gemini-2.5-flash" }
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent")
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            return try {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw SpeechEngineException("Gemini API HTTP $code")
                }
                text
            } catch (e: SpeechEngineException) {
                throw e
            } catch (e: Exception) {
                throw SpeechEngineException("Gemini request failed: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
    }
}
