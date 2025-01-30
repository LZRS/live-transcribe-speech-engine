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

import com.google.audio.asr.RepeatingRecognitionSession.PostHandler
import com.google.common.base.Objects
import com.google.common.flogger.FluentLogger
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.lang.ref.WeakReference

/**
 * Handles results as they come in from the recognition module and posts them back to the
 * RepeatingRecognitionSession.
 */
internal class TranscriptionResultReceiver(postHandler: PostHandler?) : SpeechSessionListener {
    private val postHandlerRef: WeakReference<PostHandler?>

    init {
        this.postHandlerRef = WeakReference<PostHandler?>(postHandler)
    }

    override fun onSessionFatalError(sessionID: Int, error: Throwable?) {
        TranscriptionResultReceiver.Companion.logger.atSevere().withCause(error)
            .log("Session #%d ended fatally.", sessionID)
        post(
            RequestForRecognitionThread.Companion.newBuilder()
                .setAction(
                    if (errorIndicatesLossOfConnection(error))
                        RequestForRecognitionThread.Action.HANDLE_NETWORK_CONNECTION_FATAL_ERROR
                    else
                        RequestForRecognitionThread.Action.HANDLE_NON_NETWORK_CONNECTION_FATAL_ERROR
                )
                .setSessionID(sessionID)
                .setErrorCause(error)
                .build()
        )
    }

    override fun onResults(sessionID: Int, result: TranscriptionResult?, resultsAreFinal: Boolean) {
        post(
            RequestForRecognitionThread.Companion.newBuilder()
                .setSessionID(sessionID)
                .setAction(RequestForRecognitionThread.Action.POST_RESULTS)
                .setResult(result, resultsAreFinal)
                .build()
        )
    }

    override fun onDoneListening(sessionID: Int) {
        TranscriptionResultReceiver.Companion.logger.atInfo()
            .log("Session #%d scheduled to be ended gracefully.", sessionID)
        post(sessionID, RequestForRecognitionThread.Action.REQUEST_TO_END_SESSION)
    }

    override fun onOkToTerminateSession(sessionID: Int) {
        TranscriptionResultReceiver.Companion.logger.atInfo()
            .log("Session #%d scheduled to be terminated.", sessionID)
        post(sessionID, RequestForRecognitionThread.Action.OK_TO_TERMINATE_SESSION)
    }

    private fun errorIndicatesLossOfConnection(error: Throwable?): Boolean {
        val isGrpcError = error is StatusRuntimeException
        if (isGrpcError) {
            return Objects.equal(Status.fromThrowable(error), Status.UNAVAILABLE)
        }
        return false
    }

    private fun post(sessionID: Int, request: RequestForRecognitionThread.Action?) {
        post(
            RequestForRecognitionThread.Companion.newBuilder()
                .setAction(request)
                .setSessionID(sessionID)
                .build()
        )
    }

    private fun post(request: RequestForRecognitionThread?) {
        val postHandler = postHandlerRef.get()
        if (postHandler == null) {
            return
        }
        postHandler.post(request)
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()
    }
}
