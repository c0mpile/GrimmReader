package com.c0mpile.grimmreader.core.tts

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface TtsModule {
    @Binds
    fun speechOutput(speech: SystemSpeech): SpeechOutput
}
