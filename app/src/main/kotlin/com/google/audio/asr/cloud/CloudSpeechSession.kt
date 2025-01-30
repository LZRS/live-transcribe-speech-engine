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
// The CloudSpeechSession streams audio to the server until the endpointer tells it to stop. It
// therefore must be repeatedly reopened for continuous transcription. The response observer gets
// data back from the server. Our CloudSpeechStreamObserver extracts the speech and the confidence
// and passes the data to a SpeechSessionListener, which helps to aggregate
// TranscriptionResults and manage the repeatedly reopening sessions.
package com.google.audio.asr.cloud

import com.google.audio.StreamingAudioEncoder
import com.google.audio.StreamingAudioEncoder.CodecType
import com.google.audio.StreamingAudioEncoder.EncoderException
import com.google.audio.asr.CloudSpeechSessionParams
import com.google.audio.asr.SpeechRecognitionModelOptions
import com.google.audio.asr.SpeechRecognitionModelOptions.SpecificModel
import com.google.audio.asr.SpeechSession
import com.google.audio.asr.SpeechSessionListener
import com.google.cloud.speech.v1p1beta1.RecognitionConfig
import com.google.cloud.speech.v1p1beta1.RecognitionConfig.AudioEncoding
import com.google.cloud.speech.v1p1beta1.SpeechContext
import com.google.cloud.speech.v1p1beta1.SpeechGrpc
import com.google.cloud.speech.v1p1beta1.SpeechGrpc.SpeechStub
import com.google.cloud.speech.v1p1beta1.StreamingRecognitionConfig
import com.google.cloud.speech.v1p1beta1.StreamingRecognizeRequest
import com.google.common.flogger.FluentLogger
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.stub.StreamObserver
import org.joda.time.Duration
import java.io.IOException

/**
 * Lightweight wrapper around the GRPC Google Cloud Speech API. It can handle one streaming
 * recognition request at a time.
 */
class CloudSpeechSession(
    private val params: CloudSpeechSessionParams,
    private val speechSessionListener: SpeechSessionListener?,
    private val sampleRateHz: Int,
    private val channel: ManagedChannel?
) : SpeechSession() {
    private var speechClient: SpeechStub? = null

    /**
     * The stream observer and cloud interaction functions are marked protected so that they may be
     * replaced with a local server simulation in testing.
     */
    private var responseObserver: CloudSpeechStreamObserver? = null

    private var requestObserver: StreamObserver<StreamingRecognizeRequest?>? = null

    private var stillSendingRequests = false

    private val encoder: StreamingAudioEncoder
    private var encoderIsRequested = false
    private var encoderIsSupported = false

    /*
   * @param speechSessionListener Listener for recognition responses.
   * @param sampleRateHz Sample rate of microphone data.
   * @param channel The channel to use for cloud communication.
   */
    init {
        this.encoder = StreamingAudioEncoder()
    }

    /** Starts a streaming speech recognition request.  */
    @Synchronized
    public override fun initImpl(
        modelOptions: SpeechRecognitionModelOptions, chunkSizeSamples: Int
    ) {
        if (chunkSizeSamples < 0.050 * sampleRateHz) {
            logger.atWarning().log(
                "Your buffer size is less than 50ms, you may have poor performance getting "
                        + "streaming results."
            )
        }
        responseObserver = makeResponseObserver(speechSessionListener)
        encoderIsRequested = params.getEncoderParams().getEnableEncoder()
        encoderIsSupported =
            StreamingAudioEncoder.Companion.isEncoderSupported(params.getEncoderParams().getCodec())
        if (usingEncoder()) {
            try {
                encoder.init(
                    sampleRateHz,
                    params.getEncoderParams().getCodec(),
                    params.getEncoderParams().getAllowVbr()
                )
            } catch (e: EncoderException) {
                e.printStackTrace()
                logger.atSevere().log("Encoder could not be created. Using uncompressed audio.")
                encoderIsRequested = false
            } catch (e: IOException) {
                e.printStackTrace()
                logger.atSevere().log("Encoder could not be created. Using uncompressed audio.")
                encoderIsRequested = false
            }
        }
        initServer(modelOptions)

        stillSendingRequests = true
    }

    /** Returns true when the encoder is being used.  */
    fun usingEncoder(): Boolean {
        return encoderIsRequested && encoderIsSupported
    }

    private fun makeResponseObserver(
        speechSessionListener: SpeechSessionListener?
    ): CloudSpeechStreamObserver {
        return CloudSpeechStreamObserver(
            params.getObserverParams(), speechSessionListener, sessionID()
        )
    }

    /**
     * Sends an audio buffer to the Cloud Speech Server.
     *
     * @param buffer 16 bit LinearPCM byte array.
     * @param offset first element of buffer to use.
     * @param count number of elements of buffer to use.
     * @return true if audio data was processed, false if session was already requested to close. You
     * should wait for the recognition listener passed into the constructor to receive
     * OK_TO_TERMINATE before destroying the session.
     */
    @Synchronized
    public override fun processAudioBytesImpl(buffer: ByteArray, offset: Int, count: Int): Boolean {
        if (!isStillSendingRequests()) {
            return false
        }

        if (usingEncoder()) {
            val encoded = encoder.processAudioBytes(buffer, offset, count)
            if (encoded.size > 0) {
                streamToServer(encoded, 0, encoded.size)
            }
        } else {
            streamToServer(buffer, offset, count)
        }

        if (CLOSE_SESSION_AFTER_EXTENDED_SILENCE_DURATION.isShorterThan(
                responseObserver!!.timeSinceLastServerActivity()
            )
        ) {
            logger.atInfo().log(
                "Session #%d scheduled to be ended due to extended silence.", sessionID()
            )
            requestCloseSession()
        }
        return true
    }

    private fun isStillSendingRequests(): Boolean {
        return stillSendingRequests && responseObserver!!.isStillListening()
    }

    /**
     * Closes the current recognition request on the client end. This does not immediately end the
     * session. Only once the server acknowledges the closing of the session is communication
     * complete.
     */
    @Synchronized
    public override fun requestCloseSessionImpl() {
        if (stillSendingRequests) {
            stillSendingRequests = false
            if (usingEncoder()) {
                // Get any remaining output from the codec and stop.
                val data = encoder.flushAndStop()
                streamToServer(data, 0, data.size)
            }
            closeServer()
        }
    }

    override fun requiresNetworkConnection(): Boolean {
        return true
    }

    private fun initServer(modelOptions: SpeechRecognitionModelOptions) {
        this.speechClient = SpeechGrpc.newStub(channel)
        requestObserver = speechClient!!.streamingRecognize(responseObserver)

        // Build and send a StreamingRecognizeRequest containing the parameters for
        // processing the audio.
        val speechContext = SpeechContext.getDefaultInstance()

        var encodingType = AudioEncoding.LINEAR16
        if (usingEncoder()) {
            when (encoder.getCodecType()) {
                CodecType.AMRWB -> encodingType = AudioEncoding.AMR_WB
                CodecType.FLAC -> encodingType = AudioEncoding.FLAC
                CodecType.OGG_OPUS -> encodingType = AudioEncoding.OGG_OPUS
                else -> {}
            }
        }
        val configBuilder =
            RecognitionConfig.newBuilder()
                .setEncoding(encodingType)
                .setSampleRateHertz(sampleRateHz)
                .setAudioChannelCount(1)
                .setEnableAutomaticPunctuation(true)
                .setEnableWordConfidence(true)
                .setEnableWordTimeOffsets(true)
                .addSpeechContexts(speechContext)
                .setLanguageCode(modelOptions.getLocale())
                .setProfanityFilter(params.getFilterProfanity())
                .addSpeechContexts(
                    SpeechContext.newBuilder()
                        .addAllPhrases(modelOptions.getBiasWordsList())
                )

        val strbuilder =
            StreamingRecognitionConfig.newBuilder()
                .setInterimResults(true)
                .setSingleUtterance(false)
        when (modelOptions.getModel()) {
            SpecificModel.VIDEO -> {
                if (modelOptions.getLocale() != "en-US") {
                    logger.atSevere().log("Only en-US is supported by YouTube Livestream model")
                }
                configBuilder.setModel("video")
            }

            SpecificModel.DICTATION_DEFAULT -> configBuilder.setModel("default")
        }

        val config = configBuilder.build()
        val streamingConfig = strbuilder.setConfig(config).build()

        // First request sends the configuration.
        val initial =
            StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingConfig).build()

        requestObserver!!.onNext(initial)
    }

    private fun streamToServer(buffer: ByteArray, offset: Int, count: Int) {
        val request =
            StreamingRecognizeRequest.newBuilder()
                .setAudioContent(ByteString.copyFrom(buffer, offset, count))
                .build()
        requestObserver!!.onNext(request)
    }

    private fun closeServer() {
        if (requestObserver != null) {
            // Tell the server we're done sending.
            requestObserver!!.onCompleted()
            requestObserver = null
        }
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()

        // Since the speech session times out after 5 minutes, we should try to avoid sessions reaching
        // approximately that length. If speech begins 4 mins and 30 seconds into the transcription, it
        // will likely be cut off. Therefore, we close sessions that haven't received any results in an
        // extended period of time.
        val CLOSE_SESSION_AFTER_EXTENDED_SILENCE_DURATION: Duration =
            Duration.standardMinutes(4).plus(
                Duration.standardSeconds(30)
            )
    }
}
