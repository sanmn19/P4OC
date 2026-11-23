package dev.blazelight.p4oc.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.voice.SherpaModelStore
import dev.blazelight.p4oc.core.voice.SherpaStreamingRecognizer
import dev.blazelight.p4oc.core.voice.VoiceAssistant
import dev.blazelight.p4oc.core.voice.WavPcm
import dev.blazelight.p4oc.core.voice.WhisperModel
import dev.blazelight.p4oc.core.voice.WhisperModelStore
import dev.blazelight.p4oc.core.voice.WhisperTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHERPA_SAMPLE_CHUNK = 1_600

class VoiceSettingsViewModel constructor(
    private val settingsDataStore: SettingsDataStore,
    private val modelStore: WhisperModelStore,
    private val sherpaStore: SherpaModelStore,
) : ViewModel() {

    private val _selectedModel = MutableStateFlow(WhisperModel.DEFAULT)
    val selectedModel: StateFlow<WhisperModel> = _selectedModel.asStateFlow()

    val modelStates = modelStore.states
    val sherpaState = sherpaStore.state

    private val _assistant = MutableStateFlow(VoiceAssistant.DEFAULT)
    val assistant: StateFlow<VoiceAssistant> = _assistant.asStateFlow()

    fun selectAssistant(assistant: VoiceAssistant) {
        _assistant.value = assistant
        viewModelScope.launch { settingsDataStore.setVoiceAssistantId(assistant.id) }
    }

    fun downloadSherpa() {
        viewModelScope.launch { sherpaStore.download() }
    }

    fun deleteSherpa() = sherpaStore.delete()

    private val _sampleResult = MutableStateFlow<String?>(null)
    val sampleResult: StateFlow<String?> = _sampleResult.asStateFlow()

    /** Debug-only: feeds the bundled sample through the streaming sherpa model. */
    fun transcribeSherpaSample(wavBytes: ByteArray) {
        viewModelScope.launch {
            _sampleResult.value = "Transcribing…"
            if (!sherpaStore.isReady()) {
                _sampleResult.value = "Download the Sherpa model first."
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val samples = WavPcm.toFloatSamples(wavBytes)
                    val recognizer = SherpaStreamingRecognizer(sherpaStore)
                    recognizer.use {
                        var offset = 0
                        while (offset < samples.size) {
                            val end = minOf(offset + SHERPA_SAMPLE_CHUNK, samples.size)
                            it.accept(samples.copyOfRange(offset, end))
                            offset = end
                        }
                        it.text()
                    }
                }
            }
            _sampleResult.value = result.getOrElse { "Failed: ${it.javaClass.simpleName}" }
        }
    }

    /** Debug-only: transcribes a bundled 16 kHz speech sample to prove the native path works. */
    fun transcribeSample(wavBytes: ByteArray) {
        viewModelScope.launch {
            _sampleResult.value = "Transcribing…"
            val model = _selectedModel.value
            val file = modelStore.modelFile(model)
            if (!modelStore.isReady(model)) {
                _sampleResult.value = "Download a model first."
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val samples = WavPcm.toFloatSamples(wavBytes)
                    WhisperTranscriber(file).use { it.transcribe(samples) }
                }
            }
            _sampleResult.value = result.getOrElse { "Failed: ${it.javaClass.simpleName}" }
        }
    }

    init {
        viewModelScope.launch {
            settingsDataStore.voiceModelId.collect { id ->
                val model = WhisperModel.fromId(id)
                _selectedModel.value = model
                modelStore.select(model)
            }
        }
        viewModelScope.launch {
            settingsDataStore.voiceAssistantId.collect { id ->
                _assistant.value = VoiceAssistant.fromId(id)
            }
        }
    }

    fun selectModel(model: WhisperModel) {
        _selectedModel.value = model
        modelStore.select(model)
        viewModelScope.launch { settingsDataStore.setVoiceModelId(model.id) }
    }

    fun download(model: WhisperModel) {
        viewModelScope.launch { modelStore.download(model) }
    }

    fun delete(model: WhisperModel) = modelStore.delete(model)
}
