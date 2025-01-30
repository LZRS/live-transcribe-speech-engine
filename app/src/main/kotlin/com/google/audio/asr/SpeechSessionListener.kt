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

/** An interface for communicating recognition events to the RepeatingRecognitionSession.  */
interface SpeechSessionListener {
    /**
     * Tells the client that the recognizer has had an error from which we cannot recover. It is safe
     * to terminate the session.
     */
    fun onSessionFatalError(sessionID: Int, error: Throwable?)

    /**
     * Notifies that a new transcription result is available. If resultIsFinal is false, the results
     * are subject to change.
     */
    fun onResults(sessionID: Int, result: TranscriptionResult?, resultIsFinal: Boolean)

    /** Signals that no more audio should be sent to the recognizer.  */
    fun onDoneListening(sessionID: Int)

    /**
     * Notifies that it is safe to kill the session. Called when the recognizer is done returning
     * results.
     */
    fun onOkToTerminateSession(sessionID: Int)
}
