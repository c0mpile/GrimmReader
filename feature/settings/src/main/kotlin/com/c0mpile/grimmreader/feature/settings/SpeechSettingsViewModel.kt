package com.c0mpile.grimmreader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SpeechPrefs
import com.c0mpile.grimmreader.core.datastore.SpeechProvider
import com.c0mpile.grimmreader.core.dictionary.Pronunciation
import com.c0mpile.grimmreader.core.tts.SpeechCatalog
import com.c0mpile.grimmreader.core.tts.SystemSpeech
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpeechSettingsViewModel
    @Inject
    constructor(
        private val prefs: AppPreferences,
        private val pronunciation: Pronunciation,
        private val systemSpeech: SystemSpeech,
    ) : ViewModel() {
        val speech: StateFlow<SpeechPrefs> =
            prefs.speechPrefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SpeechPrefs())

        private val _catalog = MutableStateFlow<SpeechCatalog?>(null)

        /** The device's speech engines and the chosen engine's voices; null while the engine starts. */
        val catalog: StateFlow<SpeechCatalog?> = _catalog

        /** Whether an ElevenLabs API key is saved (null until read). */
        val hasElevenLabsKey: StateFlow<Boolean?> = pronunciation.hasKey

        /** The saved ElevenLabs voice ID, if any. */
        val elevenLabsVoice: StateFlow<String?> = pronunciation.voiceId

        init {
            viewModelScope.launch { pronunciation.refreshKey() }
        }

        fun loadCatalog() {
            viewModelScope.launch {
                _catalog.value = null
                _catalog.value = systemSpeech.catalog()
            }
        }

        /** System and ElevenLabs are exclusive: turning one on turns the other off. */
        fun setSystemSpeech(on: Boolean) = setProvider(on, SpeechProvider.SYSTEM)

        fun setElevenLabsSpeech(on: Boolean) = setProvider(on, SpeechProvider.ELEVENLABS)

        private fun setProvider(
            on: Boolean,
            provider: SpeechProvider,
        ) = viewModelScope.launch {
            when {
                on -> prefs.setSpeechProvider(provider)
                prefs.speechPrefs.first().provider == provider -> prefs.setSpeechProvider(SpeechProvider.NONE)
            }
        }

        fun setEngine(engine: String?) = viewModelScope.launch { prefs.setSpeechEngine(engine) }

        fun setVoice(voice: String?) = viewModelScope.launch { prefs.setSpeechVoice(voice) }

        fun setRate(rate: Float) = viewModelScope.launch { prefs.setSpeechRate(rate) }

        fun setPitch(pitch: Float) = viewModelScope.launch { prefs.setSpeechPitch(pitch) }

        fun speakSample(text: String) = pronunciation.speak(text)

        fun setElevenLabsKey(key: String?) = viewModelScope.launch { pronunciation.setApiKey(key) }

        fun setElevenLabsVoice(id: String?) = viewModelScope.launch { pronunciation.setVoiceId(id) }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
