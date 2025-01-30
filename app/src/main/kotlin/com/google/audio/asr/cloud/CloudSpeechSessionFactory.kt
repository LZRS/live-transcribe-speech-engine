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
package com.google.audio.asr.cloud

import androidx.annotation.GuardedBy
import com.google.audio.asr.CloudSpeechSessionParams
import com.google.audio.asr.SpeechSession
import com.google.audio.asr.SpeechSessionFactory
import com.google.audio.asr.SpeechSessionListener
import com.google.common.flogger.FluentLogger
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import org.joda.time.Duration
import java.util.concurrent.TimeUnit

/** A factory for creating cloud sessions.  */
class CloudSpeechSessionFactory(
    @field:GuardedBy("paramsLock") private var params: CloudSpeechSessionParams?,
    private val apiKey: String
) : SpeechSessionFactory {
    /** Lock for handling concurrent accesses to the `params` variable.  */
    private val paramsLock = Any()

    private var channel: ManagedChannel? = null

    override fun create(listener: SpeechSessionListener?, sampleRateHz: Int): SpeechSession {
        if (this.channel == null) {
            this.channel = createManagedChannel(apiKey)
        } else {
            ensureManagedChannelConnection()
        }
        synchronized(paramsLock) {
            return CloudSpeechSession(params, listener, sampleRateHz, channel)
        }
    }

    override fun cleanup() {
        if (channel != null) {
            channel!!.shutdown()
            try {
                if (!channel!!.awaitTermination(
                        CloudSpeechSessionFactory.Companion.TERMINATE_CHANNEL_DURATION.getStandardSeconds(),
                        TimeUnit.SECONDS
                    )
                ) {
                    channel!!.shutdownNow()
                }
            } catch (e: InterruptedException) {
                CloudSpeechSessionFactory.Companion.logger.atWarning().withCause(e)
                    .log("Channel termination failed.")
            }
            channel = null
        }
    }

    fun setParams(params: CloudSpeechSessionParams?) {
        synchronized(paramsLock) {
            this.params = params
        }
    }

    protected fun ensureManagedChannelConnection() {
        // The channel may stuck at the TRANSIENT_FAILURE state, if so, enter idle to let channel to
        // trigger creation of a new connection.
        if (ConnectivityState.TRANSIENT_FAILURE == channel!!.getState(false)) {
            CloudSpeechSessionFactory.Companion.logger.atInfo()
                .log("ManagedChannel was in TRANSIENT_FAILURE state.")
            channel!!.enterIdle()
        }
    }

    private fun createManagedChannel(apiKey: String): ManagedChannel? {
        val metadata = Metadata()
        metadata.put<String?>(
            Metadata.Key.of<String?>(
                CloudSpeechSessionFactory.Companion.HEADER_API_KEY,
                Metadata.ASCII_STRING_MARSHALLER
            ), apiKey
        )
        return ManagedChannelBuilder.forTarget(CloudSpeechSessionFactory.Companion.SERVICE_URL)
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build()
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()
        private const val SERVICE_URL = "speech.googleapis.com"
        private const val HEADER_API_KEY = "X-Goog-Api-Key"

        /** Wait 1 second for the preexisting calls to finish.  */
        private val TERMINATE_CHANNEL_DURATION: Duration = Duration.standardSeconds(1)
    }
}
