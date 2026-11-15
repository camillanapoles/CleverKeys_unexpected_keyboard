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
}
