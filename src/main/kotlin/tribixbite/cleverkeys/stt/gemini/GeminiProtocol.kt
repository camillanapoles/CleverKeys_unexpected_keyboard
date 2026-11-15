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
}
