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

import android.text.Spanned
import com.google.common.flogger.FluentLogger
import com.google.common.util.concurrent.SettableFuture
import org.joda.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.GuardedBy

/**
 * A thread-safe version of the TranscriptionResultFormatter. Please see
 * [TranscriptionResultFormatter] for notes on how to use the class.
 *
 *
 * Concurrency notes: These functions may be called from any thread. Note that the delay
 * corresponding to any particular function call depends on the length of the request queue.
 *
 *
 * This class implements a confined thread concurrency model where an internal service,
 * TranscriptionResultFormatterService, delegates requests to an instance of
 * TranscriptResultFormatter in an isolated thread. The thread remains open as long as there is work
 * to be done. If the work queue empties, the thread will close and a new one will spawn on the next
 * work request.
 */
class SafeTranscriptionResultFormatter {
    private val service: TranscriptionResultFormatterService

    /** Protects the member, confinedThread, when the worker thread is being restarted.  */
    private val threadRestartLock = Any()

    @GuardedBy("threadRestartLock")
    private var confinedThread: Thread? = null

    /**
     * A request is a single job to be executed on the worker thread, confinedThread. Its members
     * represent the various inputs/outputs of the TranscriptionResultFormatter.
     */
    private class Request(private val type: RequestType) {
        // Each of these elements is only used for a subset of requests.
        var inOptions: TranscriptionResultFormatterOptions? = null
        var inTranscriptionResult: TranscriptionResult? = null
        val outBoolean: SettableFuture<Boolean?>
        val outSpanned: SettableFuture<Spanned?>
        val outDuration: SettableFuture<Duration?>

        init {
            outBoolean = SettableFuture.create<Boolean?>()
            outSpanned = SettableFuture.create<Spanned?>()
            outDuration = SettableFuture.create<Duration?>()
        }
    }

    // Used to pass requests to TranscriptionResultFormatterService.
    private val requestQueue: BlockingQueue<Request?> = ArrayBlockingQueue<Request?>(100)

    private enum class RequestType {
        SET_OPTIONS,
        RESET,
        ADD_FINALIZED_RESULT,
        CLEAR_CURRENT_HYPOTHESIS,
        FINALIZE_CURRENT_HYPOTHESIS,
        SET_CURRENT_HYPOTHESIS,
        GET_FORMATTED_TRANSCRIPT,
        GET_MOST_RECENT_TRANSCRIPT_SEGMENT,
        GET_TRANSCRIPT_DURATION,
    }

    constructor() {
        this.service = TranscriptionResultFormatterService()
    }

    constructor(options: TranscriptionResultFormatterOptions?) {
        this.service = TranscriptionResultFormatterService(options)
    }

    private fun ensureThreadIsRunning() {
        synchronized(threadRestartLock) {
            if (confinedThread == null) {
                SafeTranscriptionResultFormatter.Companion.logger.atInfo()
                    .log("Restarting formatter request queue. %s", confinedThread)
                confinedThread = Thread(service)
                confinedThread!!.start()
            }
        }
    }

    fun setOptions(options: TranscriptionResultFormatterOptions) {
        try {
            val request = SafeTranscriptionResultFormatter.Request(RequestType.SET_OPTIONS)
            request.inOptions = options
            requestQueue.put(request)
            ensureThreadIsRunning()
        } catch (interrupted: InterruptedException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted).log(
                "setOptions %s",
                SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
            )
        }
    }

    fun reset() {
        try {
            requestQueue.put(SafeTranscriptionResultFormatter.Request(RequestType.RESET))
            ensureThreadIsRunning()
        } catch (interrupted: InterruptedException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted).log(
                "reset %s",
                SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
            )
        }
    }

    fun addFinalizedResult(resultSingleUtterance: TranscriptionResult) {
        try {
            val request = SafeTranscriptionResultFormatter.Request(RequestType.ADD_FINALIZED_RESULT)
            request.inTranscriptionResult = resultSingleUtterance
            requestQueue.put(request)
            ensureThreadIsRunning()
        } catch (interrupted: InterruptedException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted).log(
                "addFinalizedResult %s",
                SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
            )
        }
    }

    fun clearCurrentHypothesis() {
        try {
            requestQueue.put(SafeTranscriptionResultFormatter.Request(RequestType.CLEAR_CURRENT_HYPOTHESIS))
            ensureThreadIsRunning()
        } catch (interrupted: InterruptedException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted).log(
                "clearCurrentHypothesis %s",
                SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
            )
        }
    }

    fun finalizeCurrentHypothesis(): Boolean {
        try {
            val request =
                SafeTranscriptionResultFormatter.Request(RequestType.FINALIZE_CURRENT_HYPOTHESIS)
            requestQueue.put(request)
            ensureThreadIsRunning()
            return request.outBoolean.get()!!
        } catch (error: ExecutionException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(error).log(
                "finalizeCurrentHypothesis %s",
                SafeTranscriptionResultFormatter.Companion.EXECUTION_EXCEPTION_MESSAGE
            )
        } catch (interrupted: InterruptedException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted).log(
                "finalizeCurrentHypothesis %s",
                SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
            )
        }
        return false
    }

    fun setCurrentHypothesis(resultSingleUtterance: TranscriptionResult) {
        try {
            val request =
                SafeTranscriptionResultFormatter.Request(RequestType.SET_CURRENT_HYPOTHESIS)
            request.inTranscriptionResult = resultSingleUtterance
            requestQueue.put(request)
            ensureThreadIsRunning()
        } catch (interrupted: InterruptedException) {
            SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted).log(
                "setCurrentHypothesis %s",
                SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
            )
        }
    }

    val formattedTranscript: Spanned?
        get() {
            try {
                val request =
                    SafeTranscriptionResultFormatter.Request(RequestType.GET_FORMATTED_TRANSCRIPT)
                requestQueue.put(request)
                ensureThreadIsRunning()
                return request.outSpanned.get()
            } catch (error: ExecutionException) {
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(error).log(
                    "getFormattedTranscript %s",
                    SafeTranscriptionResultFormatter.Companion.EXECUTION_EXCEPTION_MESSAGE
                )
            } catch (interrupted: InterruptedException) {
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted)
                    .log(
                        "getFormattedTranscript %s",
                        SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
                    )
            }
            return null
        }

    val mostRecentTranscriptSegment: Spanned?
        get() {
            try {
                val request =
                    SafeTranscriptionResultFormatter.Request(RequestType.GET_MOST_RECENT_TRANSCRIPT_SEGMENT)
                requestQueue.put(request)
                ensureThreadIsRunning()
                return request.outSpanned.get()
            } catch (error: ExecutionException) {
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(error).log(
                    "getMostRecentTranscriptSegment %s",
                    SafeTranscriptionResultFormatter.Companion.EXECUTION_EXCEPTION_MESSAGE
                )
            } catch (interrupted: InterruptedException) {
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted)
                    .log(
                        "getMostRecentTranscriptSegment %s",
                        SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
                    )
            }
            return null
        }

    val transcriptDuration: Duration?
        get() {
            try {
                val request =
                    SafeTranscriptionResultFormatter.Request(RequestType.GET_TRANSCRIPT_DURATION)
                requestQueue.put(request)
                ensureThreadIsRunning()
                return request.outDuration.get()
            } catch (error: ExecutionException) {
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(error).log(
                    "getTranscriptDuration %s",
                    SafeTranscriptionResultFormatter.Companion.EXECUTION_EXCEPTION_MESSAGE
                )
            } catch (interrupted: InterruptedException) {
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted)
                    .log(
                        "getTranscriptDuration %s",
                        SafeTranscriptionResultFormatter.Companion.INTERRUPTED_EXCEPTION_MESSAGE
                    )
            }
            return null
        }

    /**
     * A service to be run on a separate thread that performs the formatting logic. The formatting
     * logic is controlled by the TranscriptionResultFormatter instance (which is not thread-safe).
     * Each job is sent to this class by adding a request into requestQueue. Jobs will be executed in
     * the order that they are placed in the queue.
     *
     *
     * If the queue remains empty for longer than 15 seconds, the run() method will complete and
     * the parent thread will end. However, it may be restarted in a new thread.
     */
    private inner class TranscriptionResultFormatterService : Runnable {
        private val impl: TranscriptionResultFormatter

        internal constructor() {
            impl = TranscriptionResultFormatter()
        }

        internal constructor(options: TranscriptionResultFormatterOptions?) {
            impl = TranscriptionResultFormatter(options)
        }

        // Concurrency notes: As long as this task does not make callbacks into client code or
        // spawn additional tasks, there is no risk of it deadlocking. Be very careful in modifying it.
        override fun run() {
            Thread.currentThread().setName("SafeTranscriptionResultFormatterThread")
            try {
                while (true) {
                    // Try and pull from the queue. If the queue is empty for more than 15 seconds, complete
                    // the thread. The parent class will start a new thread to process this service again if
                    // another task arrives.
                    val request = requestQueue.poll(15, TimeUnit.SECONDS)
                    if (request == null) {
                        // We are ready to terminate the thread.
                        synchronized(threadRestartLock) {
                            SafeTranscriptionResultFormatter.Companion.logger.atInfo()
                                .log("Formatter request queue is exhausted. %s", confinedThread)
                            // Setting confinedThread to null is very important. This is how we signal in a
                            // synchronizable way that this thread is no longer relevant. The parent object
                            // can restart a new thread if more requests come in.
                            confinedThread = null
                        }
                        return
                    }
                    when (request.type) {
                        RequestType.SET_OPTIONS -> impl.setOptions(request.inOptions)
                        RequestType.RESET -> impl.reset()
                        RequestType.ADD_FINALIZED_RESULT -> impl.addFinalizedResult(request.inTranscriptionResult)
                        RequestType.CLEAR_CURRENT_HYPOTHESIS -> impl.clearCurrentHypothesis()
                        RequestType.FINALIZE_CURRENT_HYPOTHESIS -> request.outBoolean.set(impl.finalizeCurrentHypothesis())
                        RequestType.SET_CURRENT_HYPOTHESIS -> impl.setCurrentHypothesis(request.inTranscriptionResult)
                        RequestType.GET_FORMATTED_TRANSCRIPT -> request.outSpanned.set(impl.getFormattedTranscript())
                        RequestType.GET_MOST_RECENT_TRANSCRIPT_SEGMENT -> request.outSpanned.set(
                            impl.getMostRecentTranscriptSegment()
                        )

                        RequestType.GET_TRANSCRIPT_DURATION -> request.outDuration.set(impl.getTranscriptDuration())
                    }
                }
            } catch (interrupted: InterruptedException) {
                // Interrupting causes the service to stop.
                SafeTranscriptionResultFormatter.Companion.logger.atSevere().withCause(interrupted)
                    .log("Formatter service has been interrupted")
            }
        }
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()

        private const val EXECUTION_EXCEPTION_MESSAGE = "request failed."
        private const val INTERRUPTED_EXCEPTION_MESSAGE = "was interrupted."
    }
}
