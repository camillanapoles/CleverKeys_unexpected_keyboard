package tribixbite.cleverkeys.stt.gemini

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM protocol helpers for Gemini audio STT (WAV framing, JSON request body,
 * response transcript extraction). Kept free of `android.*` imports so it runs in
 * `runPureTests`.
 */
object GeminiProtocol {

    const val DEFAULT_SAMPLE_RATE = 16_000

    /** Wraps raw 16-bit mono little-endian PCM in a standard 44-byte RIFF/WAVE header. */
    fun buildWavBytes(pcmBytes: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): ByteArray {
        val byteRate = sampleRate * 2
        val totalDataLen = pcmBytes.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // PCM format = 1
        header.putShort(1) // Mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // Block align
        header.putShort(16) // Bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.size)
        val out = ByteArray(44 + pcmBytes.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcmBytes, 0, out, 44, pcmBytes.size)
        return out
    }

    /** Builds the Gemini `generateContent` JSON request body with inline WAV audio. */
    fun buildRequestBody(base64Audio: String, prompt: String): String {
        val inlineData = JSONObject()
            .put("mime_type", "audio/wav")
            .put("data", base64Audio)
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", inlineData))
        val contents = JSONArray()
            .put(JSONObject().put("parts", parts))
        val generationConfig = JSONObject()
            .put("temperature", 0.0)
        return JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
            .toString()
    }

    /** Extracts the transcribed text from Gemini's JSON response. */
    fun parseGeminiTranscript(responseJson: String): String {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
            if (partText.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(partText)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Returns true when [model] requires the Gemini Files + Interactions API
     * (e.g. `gemini-3.5-transcribe` Multimodal mode) instead of `:generateContent`.
     */
    fun isInteractionsModel(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("gemini-3.5-transcribe") || m.endsWith("-transcribe")
    }

    data class UploadedFileInfo(
        val name: String,
        val uri: String,
        val state: String,
    )

    /** Parses the response from Gemini Files API upload or file-status GET. */
    fun parseUploadedFileInfo(responseJson: String): UploadedFileInfo {
        val root = JSONObject(responseJson)
        val fileObj = root.optJSONObject("file") ?: root
        val name = fileObj.optString("name", "").trim()
        val uri = fileObj.optString("uri", "").trim()
        val state = fileObj.optString("state", root.optString("state", "")).trim().uppercase()
        return UploadedFileInfo(name = name, uri = uri, state = state)
    }

    /**
     * Builds the `/v1beta/interactions` JSON body for `gemini-3.5-transcribe`.
     * Uses `transcription_config.mode = "smart"` for Multimodal mode by default.
     */
    fun buildInteractionBody(
        model: String,
        fileUri: String,
        smartMode: Boolean = true,
    ): String {
        val inputItem = JSONObject()
            .put("type", "audio")
            .put("uri", fileUri)
            .put("mime_type", "audio/wav")
        val transcriptionConfig = JSONObject()
            .put("mode", if (smartMode) "smart" else "verbatim")
        val generationConfig = JSONObject()
            .put("transcription_config", transcriptionConfig)
        return JSONObject()
            .put("model", model.trim().ifEmpty { "gemini-3.5-transcribe" })
            .put("input", JSONArray().put(inputItem))
            .put("generation_config", generationConfig)
            .toString()
    }

    data class InteractionStatus(
        val id: String,
        val status: String,
        val transcript: String,
    )

    /** Parses the `/v1beta/interactions` create/poll JSON response. */
    fun parseInteractionStatus(responseJson: String): InteractionStatus {
        val root = JSONObject(responseJson)
        val id = root.optString("id", "").trim()
        val status = root.optString("status", "").trim().lowercase()
        val transcript = extractInteractionText(root)
        return InteractionStatus(id = id, status = status, transcript = transcript)
    }

    /**
     * Extracts model-produced transcript text from an Interactions API response,
     * mirroring OpenLess's `extract_interaction_text` traversal (`output_text`,
     * `outputs`, `steps`, `output` with `type in {"text", "model_output"}`).
     */
    fun extractInteractionText(root: JSONObject): String {
        val direct = root.optString("output_text", "").trim()
        if (direct.isNotEmpty()) return direct

        for (key in listOf("outputs", "steps", "output")) {
            val value = root.opt(key) ?: continue
            val sb = StringBuilder()
            collectInteractionText(value, sb)
            val text = sb.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun collectInteractionText(value: Any?, sb: StringBuilder) {
        when (value) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectInteractionText(value.opt(i), sb)
                }
            }
            is JSONObject -> {
                if (value.has("type")) {
                    val kind = value.optString("type", "")
                    if (kind != "text" && kind != "model_output") {
                        return
                    }
                }
                val text = value.optString("text", "")
                if (text.isNotEmpty()) {
                    sb.append(text)
                } else if (value.has("content")) {
                    collectInteractionText(value.opt("content"), sb)
                }
                if (!value.has("text") && value.has("output")) {
                    collectInteractionText(value.opt("output"), sb)
                }
            }
        }
    }

    /**
     * OpenLess-compatible silence auto-stop state machine (`silence_auto_stop.rs`):
     * - 3 consecutive blocks with RMS >= 0.02f marks speech detected.
     * - After speech is detected, 3.0s of silence triggers [SilenceDecision.STOP].
     * - If no speech is detected within 10.0s, triggers [SilenceDecision.CANCEL].
     */
    enum class SilenceDecision {
        STOP,
        CANCEL,
    }

    class SilenceAutoStop(
        private val startedAtMs: Long,
        private val silenceAfterSpeechMs: Long = DEFAULT_SILENCE_AFTER_SPEECH_MS,
        private val noSpeechCancelMs: Long = DEFAULT_NO_SPEECH_CANCEL_MS,
        private val speechLevelThreshold: Float = SPEECH_LEVEL_THRESHOLD,
        private val minSpeechBlocks: Int = MIN_SPEECH_BLOCKS,
    ) {
        private var speechDetected = false
        private var consecutiveSpeechBlocks = 0
        private var lastSpeechAtMs: Long? = null
        private var decided = false

        fun onLevel(level: Float, nowMs: Long): SilenceDecision? {
            if (decided) return null
            if (level >= speechLevelThreshold) {
                consecutiveSpeechBlocks++
                if (consecutiveSpeechBlocks >= minSpeechBlocks) {
                    speechDetected = true
                    lastSpeechAtMs = nowMs
                }
            } else {
                consecutiveSpeechBlocks = 0
            }
            val decision = if (speechDetected) {
                val last = lastSpeechAtMs
                if (last != null && nowMs - last >= silenceAfterSpeechMs) SilenceDecision.STOP else null
            } else if (nowMs - startedAtMs >= noSpeechCancelMs) {
                SilenceDecision.CANCEL
            } else {
                null
            }
            if (decision != null) decided = true
            return decision
        }

        companion object {
            const val SPEECH_LEVEL_THRESHOLD = 0.0015f
            const val MIN_SPEECH_BLOCKS = 3
            const val DEFAULT_SILENCE_AFTER_SPEECH_MS = 3_000L
            const val DEFAULT_NO_SPEECH_CANCEL_MS = 10_000L
        }
    }

    /** Computes RMS energy level of a PCM slice in `[-1f, 1f]`. */
    fun computeRmsLevel(pcm: FloatArray, offset: Int = 0, length: Int = pcm.size - offset): Float {
        if (length <= 0) return 0f
        var sumSq = 0.0
        val end = (offset + length).coerceAtMost(pcm.size)
        val count = end - offset
        if (count <= 0) return 0f
        for (i in offset until end) {
            val s = pcm[i].toDouble()
            sumSq += s * s
        }
        return kotlin.math.sqrt(sumSq / count).toFloat()
    }
}
