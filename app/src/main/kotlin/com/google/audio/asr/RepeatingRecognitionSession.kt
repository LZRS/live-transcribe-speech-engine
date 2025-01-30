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

import com.google.audio.CircularByteBuffer
import com.google.audio.NetworkConnectionChecker
import com.google.audio.SampleProcessorInterface
import com.google.audio.SpeakerIDLabeler
import com.google.audio.SpeakerIdInfo
import com.google.audio.asr.TranscriptionResult.Word
import com.google.audio.asr.TranscriptionResultUpdatePublisher.ResultSource
import com.google.audio.asr.TranscriptionResultUpdatePublisher.UpdateType
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.flogger.FluentLogger
import org.joda.time.Duration
import org.joda.time.Instant
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.IntFunction
import java.util.stream.Collectors
import java.util.stream.IntStream
import kotlin.math.ceil

/**
 * Repeatedly runs recognition sessions, starting a new session whenever one terminates, until
 * stopped.
 *
 *
 * Incoming is speech is timestamped as time since epoch in milliseconds.
 *
 *
 * In between sessions, some buffering is done, but if the internal session is closed for more
 * than SECONDS_TO_STORE_BETWEEN_SESSIONS, audio will be lost.
 *
 *
 * This class was intended to be used from a thread where timing is not critical (i.e. do not
 * call this in a system audio callback). Network calls may be made during all of the functions
 * inherited from SampleProcessorInterface.
 *
 *
 * Results delivered via a TranscriptionResultUpdatePublisher are done so asynchronously. All
 * public functions that are not a part of the SampleProcessorInterface API may be called from any
 * thread at any time.
 *
 *
 * TranscriptionResultUpdatePublisher callbacks are delivered from a thread on a separate thread
 * pool. You will never get two callbacks to the same TranscriptionResultUpdatePublisher instance at
 * the same time.
 */
// Threading notes:
//
// Recognition thread:
// This thread is owned by whatever system is providing the class with audio (one that treats this
// generically as a SampleProcessorInterface, interacting only with the init(), processAudioBytes(),
// and stop() methods). This is the thread that is doing most of the work of the class (session
// management, audio buffering and processing, network checks, etc.). Be aware that it can make
// network calls and do other expensive actions that should not be placed in a system audio
// callback.
//
// Note that your audio engine should never call init(), processAudioBytes(), and stop()
// concurrently.
//
// Results thread:
// This thread is not exposed outside of this class. It is controlled by the CloudSpeechSession and
// alerts the TranscriptionResultReceiver when the recognition has a result from the server. It
// posts requests to the recognition thread via that 'requests' member below. If the speech session
// implementation is single threaded, this thread and the recognition thread are the same thread.
//
// Other threads:
// Public methods other than init(), processAudioBytes(), and stop() may be called from threads
// other than the recognition thread. They are thread-safe.
class RepeatingRecognitionSession private constructor(builder: Builder) : SampleProcessorInterface {
    // All threads but the recognition thread may post to this queue, only the recognition thread
    // will read from it.
    private val requests = ConcurrentLinkedQueue<RequestForRecognitionThread?>()

    // Shared between client threads and recognizer thread.
    private val repeatedSessionIsInitialized = AtomicBoolean(false)
    private val modelOptions = AtomicReference<SpeechRecognitionModelOptions>()
    private val callbackRefs: ConcurrentLinkedQueue<TranscriptionResultPublisherReference?>
    private val transcriptionErrorPublisher: TranscriptionErrorPublisher?

    // The client may have retained a reference to the formatter, so it is assumed to be accessible
    // from multiple threads. However, it is a thread-safe object.
    private val resultFormatter: SafeTranscriptionResultFormatter

    /* ----------------- END: MEMBERS THAT ARE SHARED ACROSS MULTIPLE THREADS ------------------- */ /* --------------- BEGIN: MEMBERS THAT ARE ACCESSED ONLY FROM RESULTS THREAD ---------------- */
    /** An interface for posting requests to the RepeatingRecognitionSession.  */
    interface PostHandler {
        fun post(request: RequestForRecognitionThread?)
    }

    private val postHandler: PostHandler
    private val speechSessionListener: SpeechSessionListener

    /* ---------------- END: MEMBERS THAT ARE ACCESSED ONLY FROM RESULTS THREAD ----------------- */ /* ------------- BEGIN: MEMBERS THAT ARE ACCESSED ONLY FROM RECOGNITION THREAD -------------- */
    /**
     * Used to keep track of the current session. This is not only used for numbering incoming
     * sessions, but also to prevent old sessions from updating the state of this object. Old sessions
     * may persist and send results back to RepeatingRecognitionSession after a call to reset() or
     * after stop() and init() are called in quick succession. Sessions IDs will be monotonically
     * increasing, but are not guaranteed to be contiguous.
     */
    private var currentSessionID = -1

    // Members related to session management.
    private var currentSession: SpeechSession? = null
    private val sessionFactory: SpeechSessionFactory
    private val sampleRateHz: Int
    private var chunkSizeSamples = 0

    private var isStopped = false
    private var okToTerminateSession = false

    private var leftoverBytes: CircularByteBuffer? = null
    private var leftoverBytesAllocation: ByteArray? // Exists to avoid repeated allocations.
    private var leftoverBytesReader: CircularByteBuffer.Reader? = null

    /**
     * Allows the RepeatingRecognitionSession to stall session creation when there is a network error
     * until connection is regained. If not provided, a short delay will happen after connection loss
     * to prevent a rapid recreation of sessions.
     */
    private val networkCheck: NetworkConnectionChecker?

    private var hadNetworkConnectionError = false
    private var lastInitSessionTimestampWithoutNetworkChecker = Instant(0)
    private val speechDetector: SpeechDetectionPolicy
    private val diarizer: SpeakerIDLabeler

    /** Passes results back to registered listeners.  */
    private val resultsDeliveryService: ExecutorService

    /**
     * Keeps track of time that the last session ended. This will be used to log how long reconnection
     * takes.
     */
    private var endSessionRequestTime: Optional<Instant?> = Optional.absent<Instant?>()

    /* -------------- END: MEMBERS THAT ARE ACCESSED ONLY FROM RECOGNITION THREAD --------------- */ /*
   * A specialized reference for {@link TranscriptionResultUpdatePublisher} that allows us to fix
   * the {@link TranscriptionResultUpdatePublisher.ResultSource} which the callback expect to
   * handle.
   */
    private class TranscriptionResultPublisherReference
        (
        referent: TranscriptionResultUpdatePublisher?,
        val source: ResultSource
    ) : WeakReference<TranscriptionResultUpdatePublisher?>(referent)

    init {
        this.postHandler =
            PostHandler { request: RequestForRecognitionThread? -> requests.add(request) }
        this.speechSessionListener = TranscriptionResultReceiver(postHandler)
        this.resultFormatter = builder.resultFormatter
        this.sampleRateHz = builder.sampleRateHz
        this.sessionFactory = builder.sessionFactory!!
        this.modelOptions.set(builder.modelOptions)
        this.networkCheck = builder.networkCheck
        this.speechDetector = builder.speechDetector
        this.diarizer = builder.diarizer
        this.callbackRefs = builder.callbackRefs
        this.resultsDeliveryService = builder.resultsDeliveryService
        this.transcriptionErrorPublisher = builder.transcriptionErrorPublisher

        RepeatingRecognitionSession.Companion.maxNumSamplesToStoreBetweenSessions =
            ceil((SECONDS_TO_STORE_BETWEEN_SESSIONS.getStandardSeconds() * sampleRateHz).toDouble()).toInt()
    }

    // Should only be called on the recognition thread. See threading notes above.
    override fun init(chunkSizeSamples: Int) {
        Preconditions.checkArgument(chunkSizeSamples > 0)
        this.chunkSizeSamples = chunkSizeSamples
        speechDetector.init(chunkSizeSamples)
        diarizer.init(chunkSizeSamples)
        diarizer.setReferenceTimestamp(Instant.now())
        this.leftoverBytes =
            CircularByteBuffer(RepeatingRecognitionSession.Companion.maxNumSamplesToStoreBetweenSessions * RepeatingRecognitionSession.Companion.BYTES_PER_SAMPLE)
        this.leftoverBytesAllocation = ByteArray(leftoverBytes!!.getCapacity())
        this.leftoverBytesReader = leftoverBytes!!.newReader()
        isStopped = false
        okToTerminateSession = false
        // Create the first session.
        currentSession = sessionFactory.create(speechSessionListener, sampleRateHz)
        repeatedSessionIsInitialized.set(true)
    }

    // Should only be called on the recognition thread. See threading notes above.
    override fun processAudioBytes(samples: ByteArray?, offset: Int, length: Int) {
        check(repeatedSessionIsInitialized.get()) { "processAudioBytes() called prior to initialization!" }
        check(!isStopped) { "processAudioBytes() called while stopped!" }
        // Ignoring thread safety issues, it would be ideal to run handlePostedActions() endlessly in
        // another thread. To keep everything on the same thread, we run it in this function first
        // at the beginning to process reset events as soon as possible and again at the end to process
        // results as soon as their generated (in practice, this is mostly useful during testing).
        handlePostedActions()
        speechDetector.processAudioBytes(samples, offset, length)
        diarizer.processAudioBytes(samples, offset, length)

        // Restart the session when necessary.
        if (okToTerminateSession) {
            RepeatingRecognitionSession.Companion.logger.atInfo().log(
                "Creating a new session. Reconnection timer: %s", this.reconnectionTimerValue
            )
            currentSession = sessionFactory.create(speechSessionListener, sampleRateHz)
            okToTerminateSession = false
        }

        // If we need network, but it is unavailable, put the samples in leftovers.
        val networkRequirementsMet =
            !currentSession!!.requiresNetworkConnection() || this.isNetworkAvailable
        if (!networkRequirementsMet) {
            storeSamplesInLeftovers(samples, offset, length, false)
            // Stop the session when network is lost.
            if (currentSession!!.isInitialized()) {
                RepeatingRecognitionSession.Companion.logger.atInfo().log(
                    "Online Session #%d abandoned due to lack of network connection.",
                    currentSession!!.sessionID()
                )
                requestCurrentSessionEnd()
            }
            return
        }
        hadNetworkConnectionError = false

        // If there is no speech, end the session, and don't try to process data.
        if (!speechDetector.shouldPassAudioToRecognizer()) {
            // Buffer the speech so that when we reconnect, even a late speech detection will cause some
            // of the buffered audio to get to the server. If we drop samples we don't need to log because
            // we know it does not contain speech.
            storeSamplesInLeftovers(samples, offset, length, true)
            if (currentSession!!.isInitialized()) {
                RepeatingRecognitionSession.Companion.logger.atInfo().log(
                    "Session #%d ending due to lack of detected speech.",
                    currentSession!!.sessionID()
                )
                requestCurrentSessionEnd()
            }
            return
        }

        // Initialize the session.
        if (!currentSession!!.isInitialized()) {
            // Get the reference to the model so that the log and the session see the same version.
            val model: SpeechRecognitionModelOptions = modelOptions.get()
            currentSessionID++
            RepeatingRecognitionSession.Companion.logger.atInfo().log(
                "Starting a Session #%d in language `%s`.", currentSessionID, model.getLocale()
            )
            currentSession!!.init(model, chunkSizeSamples, currentSessionID)
        }

        tryToProcessLeftovers()

        // If the session can take requests, send samples. Otherwise, put them into the leftover queue.
        if (currentSession!!.processAudioBytes(samples, offset, length)) {
            stopReconnectionTimer()
        } else {
            storeSamplesInLeftovers(samples, offset, length, false)
        }

        handlePostedActions()
    }

    /**
     * Terminate the current session. Any results from the server after a call to stop() are not
     * guaranteed to arrive.
     */
    // Should only be called only from the MicManager on the recognition thread as a
    // SampleProcessorInterface.
    override fun stop() {
        // Handle any requests that have happened prior to now.
        handlePostedActions()
        isStopped = true
        speechDetector.stop()
        diarizer.stop()
        if (currentSession!!.isInitialized()) {
            RepeatingRecognitionSession.Companion.logger.atInfo().log(
                "Session #%d abandoned due to repeated session ending.",
                currentSession!!.sessionID()
            )
            abandonCurrentSession()
        }
        repeatedSessionIsInitialized.set(false)
    }

    /**
     * Restarts recognition, discarding the state of the currently active session. Request is
     * performed asynchronously, so this function may be called from any thread at any point during
     * the session.
     *
     *
     * Results generated after the asynchronous reset will not arrive.
     */
    // May be called from any thread.
    fun reset() {
        reset(false)
    }

    private fun reset(clearTranscript: Boolean) {
        if (!repeatedSessionIsInitialized.get()) {
            return
        }
        RepeatingRecognitionSession.Companion.logger.atInfo().log(
            "Session #%d scheduled to be abandoned due to call to reset().",
            currentSession!!.sessionID()
        )
        requests.add(
            RequestForRecognitionThread.Companion.newBuilder()
                .setAction(
                    if (clearTranscript)
                        RequestForRecognitionThread.Action.RESET_SESSION_AND_CLEAR_TRANSCRIPT
                    else
                        RequestForRecognitionThread.Action.RESET_SESSION
                )
                .build()
        )
    }

    /**
     * Restarts recognition, discarding the state of the currently active session. Request is
     * performed asynchronously, so this function may be called from any thread at any point during
     * the session. Clears returned transcript. The caller will know that
     * the reset has been completed when a TRANSCRIPT_CLEARED is received through the listener.
     */
    // May be called from any thread.
    fun resetAndClearTranscript() {
        reset(true)
    }

    /**
     * Sets the modelOptions, which may include a language change or usage of a different model.
     * Session management is performed asynchronously, so this function may be called from any thread
     * at any point during the session.
     */
    // May be called from any thread.
    fun setModelOptions(modelOptions: SpeechRecognitionModelOptions?) {
        this.modelOptions.set(modelOptions)
        RepeatingRecognitionSession.Companion.logger.atInfo()
            .log("Session scheduled to be ended due to model options change.")
        requests.add(
            RequestForRecognitionThread.Companion.newBuilder()
                .setAction(RequestForRecognitionThread.Action.REQUEST_TO_END_SESSION)
                .build()
        )
    }

    /** Gets the modelOptions, which may include a language change or usage of a different model.  */ // May be called from any thread.
    fun getModelOptions(): SpeechRecognitionModelOptions? {
        return modelOptions.get()
    }

    // Must be called prior to init() or after stop().
    fun registerCallback(
        callback: TranscriptionResultUpdatePublisher?,
        source: ResultSource
    ) {
        Preconditions.checkNotNull<TranscriptionResultUpdatePublisher?>(callback)
        val iterator = callbackRefs.iterator()
        while (iterator.hasNext()) {
            if (callback == iterator.next()!!.get()) {
                throw RuntimeException("Listener is already registered.")
            }
        }
        callbackRefs.add(TranscriptionResultPublisherReference(callback, source))
    }

    // Must be called prior to init() or after stop().
    fun unregisterCallback(callback: TranscriptionResultUpdatePublisher?) {
        Preconditions.checkNotNull<TranscriptionResultUpdatePublisher?>(callback)
        val iterator = callbackRefs.iterator()
        while (iterator.hasNext()) {
            if (callback == iterator.next()!!.get()) {
                iterator.remove()
            }
        }
    }

    /**
     * Pulls requests off of the queue and performs them. This is only to be called from the thread
     * that is calling init(), processAudioBytes(), and stop().
     *
     *
     * Internal notes: None of the tasks performed while emptying the request queue should make a
     * call to handlePostedActions() or post new requests, as this may result in results being
     * processed out of order.
     */
    private fun handlePostedActions() {
        var request = requests.poll()

        while (request != null) {
            if (request.hasSessionID() && request.sessionID() < currentSessionID) {
                // Completely ignore results for sessions that have been abandoned.
                RepeatingRecognitionSession.Companion.logger.atInfo()
                    .log("Old event from Session #%d discarded.", request.sessionID())
                request = requests.poll()
                continue
            }
            when (request.action()) {
                RequestForRecognitionThread.Action.HANDLE_NETWORK_CONNECTION_FATAL_ERROR -> {
                    RepeatingRecognitionSession.Companion.logger.atInfo()
                        .log("Closing Session #%d due to network error.", request.sessionID())
                    finalizeLeftoverHypothesis()
                    okToTerminateSession = true
                    processError(request.getErrorCause())
                    startReconnectionTimer()
                }

                RequestForRecognitionThread.Action.HANDLE_NON_NETWORK_CONNECTION_FATAL_ERROR -> {
                    RepeatingRecognitionSession.Companion.logger.atInfo()
                        .log("Closing Session #%d due to non-network error.", request.sessionID())
                    hadNetworkConnectionError = true
                    finalizeLeftoverHypothesis()
                    okToTerminateSession = true
                    processError(request.getErrorCause())
                    startReconnectionTimer()
                }

                RequestForRecognitionThread.Action.POST_RESULTS -> {
                    RepeatingRecognitionSession.Companion.logger.atInfo().log(
                        "Session #%d received result (final = %b).",
                        request.sessionID(), request.requestIsFinal()
                    )
                    processResult(request.result(), request.requestIsFinal())
                }

                RequestForRecognitionThread.Action.OK_TO_TERMINATE_SESSION -> {
                    RepeatingRecognitionSession.Companion.logger.atInfo()
                        .log("Terminating Session #%d cleanly.", request.sessionID())
                    okToTerminateSession = true
                    startReconnectionTimer()
                }

                RequestForRecognitionThread.Action.REQUEST_TO_END_SESSION -> requestCurrentSessionEnd()
                RequestForRecognitionThread.Action.RESET_SESSION -> resetInternal()
                RequestForRecognitionThread.Action.RESET_SESSION_AND_CLEAR_TRANSCRIPT -> {
                    resetInternal()
                    resultFormatter.reset()
                    sendTranscriptResultUpdated(
                        UpdateType.TRANSCRIPT_CLEARED
                    )
                }

                RequestForRecognitionThread.Action.NO_ACTION -> {}
            }
            request = requests.poll()
        }
    }

    private fun processError(errorCause: Throwable?) {
        if (transcriptionErrorPublisher != null) {
            transcriptionErrorPublisher.onError(errorCause)
        }
    }

    private fun resetInternal() {
        speechDetector.reset()
        if (currentSession!!.isInitialized()) {
            RepeatingRecognitionSession.Companion.logger.atInfo().log(
                "Session #%d abandoned due to call to reset().", currentSession!!.sessionID()
            )
            abandonCurrentSession()
        }
    }

    private fun requestCurrentSessionEnd() {
        if (repeatedSessionIsInitialized.get() && currentSession!!.isInitialized()) {
            currentSession!!.requestCloseSession()
        }
    }

    private fun abandonCurrentSession() {
        finalizeLeftoverHypothesis()
        requestCurrentSessionEnd()
        // By incrementing the session ID here, we are preventing results from the old session from
        // being processed.
        currentSessionID++
        okToTerminateSession = true
    }

    private fun tryToProcessLeftovers() {
        // Process stored samples, if there are any.
        val numLeftoverBytes = leftoverBytesReader!!.availableBytes()
        if (numLeftoverBytes > 0) {
            leftoverBytesReader!!.peek(leftoverBytesAllocation, 0, numLeftoverBytes)
            if (currentSession!!.processAudioBytes(leftoverBytesAllocation, 0, numLeftoverBytes)) {
                stopReconnectionTimer()
                leftoverBytes!!.reset() // Readers get reset.
            }
        }
    }

    private fun storeSamplesInLeftovers(
        samples: ByteArray?, offset: Int, length: Int, droppingSamplesIsIntended: Boolean
    ) {
        // If we fail this, it means we passed many seconds of audio at once. This should never happen
        // under normal streaming conditions.
        Preconditions.checkArgument(length < leftoverBytes!!.getCapacity())
        val numLeftoverBytes = leftoverBytesReader!!.availableBytes()
        if (numLeftoverBytes + length > leftoverBytes!!.getCapacity()) {
            if (!droppingSamplesIsIntended) {
                RepeatingRecognitionSession.Companion.logger.atSevere()
                    .atMostEvery(5, TimeUnit.SECONDS).log(
                        "Dropped audio between sessions. [atMostEvery 5s]"
                    )
            }
            leftoverBytesReader!!.advance((numLeftoverBytes + length) - leftoverBytes!!.getCapacity())
        }
        leftoverBytes!!.write(samples, offset, length)
    }

    private val isNetworkReconnectionTimeout: Boolean
        /**
         * Check reconnect timeout to prevent connecting fail repeatedly in a short time.
         *
         * @return true if establishing connection is allowed.
         */
        get() {
            if (RECREATE_SESSION_IF_NO_NETWORKCHECKER_DURATION.isShorterThan(
                    Duration(
                        lastInitSessionTimestampWithoutNetworkChecker,
                        Instant.now()
                    )
                )
            ) {
                // Allow to create a new session every second.
                lastInitSessionTimestampWithoutNetworkChecker = Instant.now()
                return true
            }
            return false
        }

    private val isNetworkAvailable: Boolean
        get() {
            if (networkCheck != null) {
                return networkCheck.isConnected()
            } else if (hadNetworkConnectionError) {
                return this.isNetworkReconnectionTimeout
            } else {
                return true
            }
        }

    protected fun processResult(result: TranscriptionResult, resultIsFinal: Boolean) {
        var result = result
        speechDetector.cueEvidenceOfSpeech()
        result = addSpeakerIDLabels(result)!!
        resultFormatter.setCurrentHypothesis(result)
        if (resultIsFinal) {
            resultFormatter.finalizeCurrentHypothesis()
        }
        sendTranscriptResultUpdated(
            if (resultIsFinal)
                UpdateType.TRANSCRIPT_FINALIZED
            else
                UpdateType.TRANSCRIPT_UPDATED
        )
    }

    private fun finalizeLeftoverHypothesis() {
        if (resultFormatter.finalizeCurrentHypothesis()) {
            sendTranscriptResultUpdated(
                UpdateType.TRANSCRIPT_FINALIZED
            )
        }
    }

    private fun startReconnectionTimer() {
        endSessionRequestTime = Optional.of<Instant?>(Instant.now())
    }

    private val reconnectionTimerValue: String
        get() {
            if (endSessionRequestTime.isPresent()) {
                val difference = Duration(
                    endSessionRequestTime.get(),
                    Instant.now()
                )
                return "<" + difference.getMillis() / 1000.0f + "s>"
            }
            return "<Timer not set>"
        }

    private fun stopReconnectionTimer() {
        if (endSessionRequestTime.isPresent()) {
            val endTime = this.reconnectionTimerValue
            RepeatingRecognitionSession.Companion.logger.atInfo()
                .log("Reconnection timer stopped: %s.", endTime)
        }
        endSessionRequestTime = Optional.absent<Instant?>()
    }

    private fun sendTranscriptResultUpdated(type: UpdateType?) {
        val transcript = resultFormatter.getFormattedTranscript()
        val segment = resultFormatter.getMostRecentTranscriptSegment()
        val iterator = callbackRefs.iterator()

        while (iterator.hasNext()) {
            val ref = iterator.next()
            val publisher = ref.get()
            val source = ref.source
            val typeToSend = type
            if (publisher == null) {
                iterator.remove()
            } else {
                resultsDeliveryService.execute(
                    Runnable {
                        synchronized(publisher) {
                            when (source) {
                                ResultSource.MOST_RECENT_SEGMENT -> publisher.onTranscriptionUpdate(
                                    segment,
                                    typeToSend
                                )

                                ResultSource.WHOLE_RESULT -> publisher.onTranscriptionUpdate(
                                    transcript,
                                    typeToSend
                                )
                            }
                        }
                    })
            }
        }
    }

    /** Returns a new proto that is labeled with speaker ID information.  */
    fun addSpeakerIDLabels(result: TranscriptionResult): TranscriptionResult? {
        // We don't know whether we'll be using word level detail or not downstream, so have the
        // diarizer process everything.
        val wholeUtteranceInfo =
            diarizer.getSpeakerIDForTimeInterval(
                TimeUtil.toInstant(result.getStartTimestamp()),
                TimeUtil.toInstant(result.getEndTimestamp())
            )
        val wordLevelInfo: MutableList<SpeakerIdInfo?> =
            ArrayList<SpeakerIdInfo?>(result.getWordLevelDetailCount())
        for (word in result.getWordLevelDetailList()) {
            wordLevelInfo.add(
                diarizer.getSpeakerIDForTimeInterval(
                    TimeUtil.toInstant(word.getStartTimestamp()),
                    TimeUtil.toInstant(word.getEndTimestamp())
                )
            )
        }
        // Protos are immutable, so to move the diarization info in, we need to make a deep copy and
        // fill in the word level data.
        return result.toBuilder()
            .setSpeakerInfo(wholeUtteranceInfo)
            .clearWordLevelDetail()
            .addAllWordLevelDetail(
                IntStream.range(0, wordLevelInfo.size)
                    .mapToObj<Word?>(
                        IntFunction { i: Int ->
                            result.getWordLevelDetail(i).toBuilder()
                                .setSpeakerInfo(wordLevelInfo.get(i))
                                .build()
                        })
                    .collect(Collectors.toList())
            )
            .build()
    }

    /** A Builder class for constructing RepeatingRecognitionSessions.  */
    class Builder private constructor() {
        // Required.
        private var sampleRateHz = 0
        private var sessionFactory: SpeechSessionFactory? = null
        private var modelOptions: SpeechRecognitionModelOptions? = null

        // Optional. Note that if you don't have either a resultFormatter or a callbackRefs there is
        // no way to get output out of the RepeatingRecognitionSession.
        private var resultFormatter = SafeTranscriptionResultFormatter()
        private var networkCheck: NetworkConnectionChecker? = null
        private var speechDetector: SpeechDetectionPolicy = AlwaysSpeechPolicy()
        private var diarizer: SpeakerIDLabeler =
            AlwaysSameSpeakerIDLabeler(SpeakerIdInfo.newBuilder().setSpeakerId(0).build())
        private val callbackRefs: ConcurrentLinkedQueue<TranscriptionResultPublisherReference?> =
            ConcurrentLinkedQueue<TranscriptionResultPublisherReference?>()
        private var transcriptionErrorPublisher: TranscriptionErrorPublisher? = null
        private val resultsDeliveryService: ExecutorService = Executors.newCachedThreadPool()

        fun build(): RepeatingRecognitionSession {
            Preconditions.checkArgument(sampleRateHz > 0)
            Preconditions.checkNotNull<SpeechRecognitionModelOptions?>(modelOptions)
            Preconditions.checkNotNull<SpeechSessionFactory?>(sessionFactory)
            return RepeatingRecognitionSession(this)
        }

        fun setSampleRateHz(sampleRateHz: Int): Builder {
            this.sampleRateHz = sampleRateHz
            return this
        }

        fun setSpeechSessionFactory(factory: SpeechSessionFactory): Builder {
            this.sessionFactory = factory
            return this
        }

        fun setSpeechRecognitionModelOptions(modelOptions: SpeechRecognitionModelOptions?): Builder {
            this.modelOptions = modelOptions
            return this
        }

        fun setNetworkConnectionChecker(networkCheck: NetworkConnectionChecker?): Builder {
            this.networkCheck = networkCheck
            return this
        }

        fun setTranscriptionResultFormatter(formatter: SafeTranscriptionResultFormatter): Builder {
            this.resultFormatter = formatter
            return this
        }

        fun setSpeechDetectionPolicy(speechDetector: SpeechDetectionPolicy): Builder {
            this.speechDetector = speechDetector
            return this
        }

        fun setSpeakerIDLabeler(diarizer: SpeakerIDLabeler): Builder {
            this.diarizer = diarizer
            return this
        }

        fun addTranscriptionResultCallback(
            callback: TranscriptionResultUpdatePublisher?,
            source: ResultSource
        ): Builder {
            Preconditions.checkNotNull<TranscriptionResultUpdatePublisher?>(callback)
            val iterator = callbackRefs.iterator()
            while (iterator.hasNext()) {
                if (callback == iterator.next()!!.get()) {
                    throw RuntimeException("Listener is already registered.")
                }
            }
            callbackRefs.add(TranscriptionResultPublisherReference(callback, source))
            return this
        }

        fun setTranscriptionErrorPublisher(publisher: TranscriptionErrorPublisher?): Builder {
            transcriptionErrorPublisher = publisher
            return this
        }
    }

    companion object {
        /* ---------------- BEGIN: MEMBERS THAT ARE SHARED ACROSS MULTIPLE THREADS ------------------ */
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()

        val SECONDS_TO_STORE_BETWEEN_SESSIONS: Duration = Duration.standardSeconds(10)
        private const val BYTES_PER_SAMPLE = 2

        // Some variables to facilitate buffering between sessions.
        private val maxNumSamplesToStoreBetweenSessions: Int
        val RECREATE_SESSION_IF_NO_NETWORKCHECKER_DURATION: Duration = Duration.standardSeconds(1)

        fun newBuilder(): Builder {
            return RepeatingRecognitionSession.Builder()
        }
    }
}
