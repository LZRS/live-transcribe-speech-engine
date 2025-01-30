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

import com.google.common.base.Preconditions

/**
 * Actions that may be performed on the session that should be executed in the recognition thread.
 *
 *
 * Most requests come from a session, but they can also come from a client triggering a reset or
 * a model change. If the request comes from a session, it will be marked with a session ID.
 */
class RequestForRecognitionThread private constructor(builder: Builder) {
    private val action: Action?
    private val result: TranscriptionResult?
    private val requestIsFinal: Boolean
    private val sessionID: Int
    val errorCause: Throwable?

    /** The action that will be executed on the audio thread of the RepeatingRecognitionSession.  */
    enum class Action {
        NO_ACTION,
        HANDLE_NETWORK_CONNECTION_FATAL_ERROR,
        HANDLE_NON_NETWORK_CONNECTION_FATAL_ERROR,
        OK_TO_TERMINATE_SESSION,
        POST_RESULTS,
        REQUEST_TO_END_SESSION,
        RESET_SESSION,
        RESET_SESSION_AND_CLEAR_TRANSCRIPT,
    }

    init {
        this.action = builder.action
        this.result = builder.result
        this.requestIsFinal = builder.requestIsFinal
        this.sessionID = builder.sessionID
        this.errorCause = builder.errorCause
    }

    fun action(): Action? {
        return action
    }

    fun hasSessionID(): Boolean {
        return sessionID != RequestForRecognitionThread.Companion.NO_SESSION
    }

    // May return NO_SESSION.
    fun sessionID(): Int {
        return sessionID
    }

    fun requestIsFinal(): Boolean {
        return requestIsFinal
    }

    fun result(): TranscriptionResult? {
        return result
    }

    /** A Builder class for RequestForRecognitionThread objects.  */
    internal class Builder private constructor() {
        private var action: Action? = Action.NO_ACTION
        private var sessionID: Int = RequestForRecognitionThread.Companion.NO_SESSION
        private var result: TranscriptionResult? = null
        private var requestIsFinal = false
        private var errorCause: Throwable? = null

        /** Notes the action to be performed. If you don't call this, no action will be requested.  */
        fun setAction(action: Action?): Builder {
            this.action = action
            return this
        }

        /** Tells the audio thread what the corresponding session ID is. Must be non-negative.  */
        fun setSessionID(sessionID: Int): Builder {
            // We use a negative value to indicate NO_SESSION. Do not assign a negative ID.
            Preconditions.checkArgument(sessionID >= 0)
            this.sessionID = sessionID
            return this
        }

        /** Adds a finalized/nonfinalized result to the request.  */
        fun setResult(result: TranscriptionResult?, requestIsFinal: Boolean): Builder {
            this.result = result
            this.requestIsFinal = requestIsFinal
            return this
        }

        fun build(): RequestForRecognitionThread {
            return RequestForRecognitionThread(this)
        }

        fun setErrorCause(errorCause: Throwable?): Builder {
            this.errorCause = errorCause
            return this
        }
    }

    companion object {
        private val NO_SESSION = -1
        fun newBuilder(): Builder {
            return RequestForRecognitionThread.Builder()
        }
    }
}
