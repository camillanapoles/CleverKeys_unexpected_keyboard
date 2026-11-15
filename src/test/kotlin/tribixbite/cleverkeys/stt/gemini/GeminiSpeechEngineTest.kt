package tribixbite.cleverkeys.stt.gemini

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.backup.SettingsValidation

class GeminiSpeechEngineTest {

    @Test
    fun buildWavBytesProducesValidRiffWaveHeader() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = GeminiProtocol.buildWavBytes(pcm, sampleRate = 16_000)

        assertThat(wav.size).isEqualTo(44 + pcm.size)
        assertThat(String(wav, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(String(wav, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(String(wav, 12, 4, Charsets.US_ASCII)).isEqualTo("fmt ")
        assertThat(String(wav, 36, 4, Charsets.US_ASCII)).isEqualTo("data")
        assertThat(wav.copyOfRange(44, wav.size)).isEqualTo(pcm)
    }

    @Test
    fun buildRequestBodyEmbedsInlineWavAndPromptWithoutApiKey() {
        val body = GeminiProtocol.buildRequestBody(
            base64Audio = "UklGRg==",
            prompt = "Transcribe cleanly.",
        )

        assertThat(body).contains("\"mime_type\":\"audio/wav\"")
        assertThat(body).contains("\"data\":\"UklGRg==\"")
        assertThat(body).contains("Transcribe cleanly.")
        assertThat(body).doesNotContain("AIza")
    }

    @Test
    fun parseGeminiTranscriptExtractsJoinedPartsText() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "Hello"},
                      {"text": "world."}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        assertThat(GeminiProtocol.parseGeminiTranscript(json)).isEqualTo("Hello world.")
    }

    @Test
    fun geminiPreferencesAreMarkedInternalAndNeverExported() {
        assertThat(SettingsValidation.isInternalPreference("voice_gemini_api_key")).isTrue()
        assertThat(SettingsValidation.isInternalPreference("voice_gemini_enabled")).isTrue()
        assertThat(SettingsValidation.isInternalPreference("voice_gemini_model")).isTrue()
        assertThat(SettingsValidation.isInternalPreference("voice_gemini_prompt")).isTrue()
    }

    @Test
    fun isInteractionsModelDetectsTranscribeModels() {
        assertThat(GeminiProtocol.isInteractionsModel("gemini-3.5-transcribe")).isTrue()
        assertThat(GeminiProtocol.isInteractionsModel(" gemini-3.5-transcribe ")).isTrue()
        assertThat(GeminiProtocol.isInteractionsModel("gemini-2.5-flash")).isFalse()
    }

    @Test
    fun parseUploadedFileInfoExtractsNameUriAndState() {
        val json = """
            {
              "file": {
                "name": "files/abc123",
                "uri": "https://generativelanguage.googleapis.com/v1beta/files/abc123",
                "state": "ACTIVE"
              }
            }
        """.trimIndent()
        val info = GeminiProtocol.parseUploadedFileInfo(json)
        assertThat(info.name).isEqualTo("files/abc123")
        assertThat(info.uri).isEqualTo("https://generativelanguage.googleapis.com/v1beta/files/abc123")
        assertThat(info.state).isEqualTo("ACTIVE")
    }

    @Test
    fun buildInteractionBodyUsesSmartMultimodalMode() {
        val body = GeminiProtocol.buildInteractionBody(
            model = "gemini-3.5-transcribe",
            fileUri = "https://generativelanguage.googleapis.com/v1beta/files/abc123",
            smartMode = true,
        )
        assertThat(body).contains("\"model\":\"gemini-3.5-transcribe\"")
        assertThat(body).contains("\"type\":\"audio\"")
        assertThat(body).contains("\"uri\":\"https://generativelanguage.googleapis.com/v1beta/files/abc123\"")
        assertThat(body).contains("\"mime_type\":\"audio/wav\"")
        assertThat(body).contains("\"mode\":\"smart\"")
    }

    @Test
    fun parseInteractionStatusExtractsModelOutputTextAndIgnoresThought() {
        val json = """
            {
              "id": "int_123",
              "status": "completed",
              "outputs": [
                {"type": "thought", "text": "internal thinking"},
                {"type": "text", "text": "Olá mundo."}
              ]
            }
        """.trimIndent()
        val parsed = GeminiProtocol.parseInteractionStatus(json)
        assertThat(parsed.id).isEqualTo("int_123")
        assertThat(parsed.status).isEqualTo("completed")
        assertThat(parsed.transcript).isEqualTo("Olá mundo.")
    }
}
