/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.audio.asr

/**
 * Speech recognizers must use this interface. Note that any initialization that if *really*
 * expensive should happen in the factory, not the speech session, as the factory will setup once
 * before streaming occurs and persist across all sessions.
 */
abstract class SpeechSession {
    /** Returns true if init has been called already.  */
    var isInitialized: Boolean = false
        private set
    private var sessionID = 0

    /** Returns true if internet is an initialization requirement.  */
    abstract fun requiresNetworkConnection(): Boolean

    /**
     * Any setup that requires network connection should happen here.
     *
     *
     * This must not be called multiple times.
     */
    fun init(
        modelOptions: SpeechRecognitionModelOptions?, bufferSizeSamples: Int, sessionID: Int
    ) {
        check(!this.isInitialized) { "Do not call initialize multiple times!" }
        this.sessionID = sessionID
        initImpl(modelOptions, bufferSizeSamples)
        this.isInitialized = true
    }

    fun sessionID(): Int {
        return sessionID
    }

    protected abstract fun initImpl(
        modelOptions: SpeechRecognitionModelOptions?, bufferSizeSamples: Int
    )

    /** Passes audio to the session, formatted as int16 samples.  */
    fun processAudioBytes(buffer: ByteArray?, offset: Int, count: Int): Boolean {
        check(this.isInitialized) { "Do not call processAudioBytes before init()!" }
        return processAudioBytesImpl(buffer, offset, count)
    }

    protected abstract fun processAudioBytesImpl(
        buffer: ByteArray?,
        offset: Int,
        count: Int
    ): Boolean

    /**
     * Begin the process of ending the speech session. The session need not be fully closed by the
     * time this function returns. To signal that the session is fully closed, use
     * SpeechSessionListener.onOkToTerminate() (the listener is passed into the session's
     * constructor).
     *
     *
     * This must not cause isInitialized to return false. This may be called multiple times.
     */
    fun requestCloseSession() {
        check(this.isInitialized) { "Do not call requestCloseSession before init()!" }
        requestCloseSessionImpl()
    }

    protected abstract fun requestCloseSessionImpl()
}
