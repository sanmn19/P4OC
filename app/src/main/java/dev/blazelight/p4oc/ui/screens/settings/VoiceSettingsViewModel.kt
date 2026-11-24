package dev.blazelight.p4oc.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
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

class VoiceSettingsViewModel constructor(
    private val settingsDataStore: SettingsDataStore,
    private val modelStore: WhisperModelStore,
) : ViewModel() {

    private val _selectedModel = MutableStateFlow(WhisperModel.DEFAULT)
    val selectedModel: StateFlow<WhisperModel> = _selectedModel.asStateFlow()

    val modelStates = modelStore.states

    private val _sampleResult = MutableStateFlow<String?>(null)
    val sampleResult: StateFlow<String?> = _sampleResult.asStateFlow()

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
            settingsDataStore.voiceModelId.collect { id -> _selectedModel.value = WhisperModel.fromId(id) }
        }
    }

    fun selectModel(model: WhisperModel) {
        _selectedModel.value = model
        viewModelScope.launch { settingsDataStore.setVoiceModelId(model.id) }
    }

    fun download(model: WhisperModel) {
        viewModelScope.launch { modelStore.download(model) }
    }

    fun delete(model: WhisperModel) = modelStore.delete(model)
}
