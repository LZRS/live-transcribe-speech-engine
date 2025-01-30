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
package com.google.audio

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.audio.asr.CloudSpeechSessionParams
import com.google.audio.asr.CloudSpeechStreamObserverParams
import com.google.audio.asr.RepeatingRecognitionSession
import com.google.audio.asr.SafeTranscriptionResultFormatter
import com.google.audio.asr.SpeechRecognitionModelOptions
import com.google.audio.asr.SpeechRecognitionModelOptions.SpecificModel
import com.google.audio.asr.TranscriptionResultFormatterOptions
import com.google.audio.asr.TranscriptionResultFormatterOptions.TranscriptColoringStyle
import com.google.audio.asr.TranscriptionResultUpdatePublisher
import com.google.audio.asr.TranscriptionResultUpdatePublisher.ResultSource
import com.google.audio.asr.TranscriptionResultUpdatePublisher.UpdateType
import com.google.audio.asr.cloud.CloudSpeechSessionFactory

class MainActivity : AppCompatActivity() {
    private var currentLanguageCodePosition = 0
    private var currentLanguageCode: String? = null

    private var audioRecord: AudioRecord? = null
    private val buffer =
        ByteArray(MainActivity.Companion.BYTES_PER_SAMPLE * MainActivity.Companion.CHUNK_SIZE_SAMPLES)

    // This class was intended to be used from a thread where timing is not critical (i.e. do not
    // call this in a system audio callback). Network calls will be made during all of the functions
    // that RepeatingRecognitionSession inherits from SampleProcessorInterface.
    private var recognizer: RepeatingRecognitionSession? = null
    private var networkChecker: NetworkConnectionChecker? = null
    private var transcript: TextView? = null

    private val transcriptUpdater =
        TranscriptionResultUpdatePublisher { formattedTranscript: Spanned?, updateType: UpdateType? ->
            runOnUiThread(
                Runnable {
                    transcript!!.setText(formattedTranscript.toString())
                })
        }

    private val readMicData = Runnable {
        if (audioRecord!!.getState() != AudioRecord.STATE_INITIALIZED) {
            return@Runnable
        }
        recognizer!!.init(MainActivity.Companion.CHUNK_SIZE_SAMPLES)
        while (audioRecord!!.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord!!.read(
                buffer,
                0,
                MainActivity.Companion.CHUNK_SIZE_SAMPLES * MainActivity.Companion.BYTES_PER_SAMPLE
            )
            recognizer!!.processAudioBytes(buffer)
        }
        recognizer!!.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        transcript = findViewById<TextView>(R.id.transcript)
        initLanguageLocale()
    }

    public override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf<String>(Manifest.permission.RECORD_AUDIO),
                MainActivity.Companion.PERMISSIONS_REQUEST_RECORD_AUDIO
            )
        } else {
            showAPIKeyDialog()
        }
    }

    public override fun onStop() {
        super.onStop()
        if (audioRecord != null) {
            audioRecord!!.stop()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (recognizer != null) {
            recognizer!!.unregisterCallback(transcriptUpdater)
            networkChecker!!.unregisterNetworkCallback()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MainActivity.Companion.PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showAPIKeyDialog()
                } else {
                    // This should nag user again if they launch without the permissions.
                    Toast.makeText(
                        this,
                        "This app does not work without the Microphone permission.",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                    finish()
                }
                return
            }

            else -> {}
        }
    }

    private fun initLanguageLocale() {
        // The default locale is en-US.
        currentLanguageCode = "en-US"
        currentLanguageCodePosition = 22
    }

    private fun constructRepeatingRecognitionSession() {
        val options =
            SpeechRecognitionModelOptions.newBuilder()
                .setLocale(currentLanguageCode) // As of 7/18/19, Cloud Speech's video model supports en-US only.
                .setModel(if (currentLanguageCode == "en-US") SpecificModel.VIDEO else SpecificModel.DICTATION_DEFAULT)
                .build()
        val cloudParams =
            CloudSpeechSessionParams.newBuilder()
                .setObserverParams(
                    CloudSpeechStreamObserverParams.newBuilder().setRejectUnstableHypotheses(false)
                )
                .setFilterProfanity(true)
                .setEncoderParams(
                    CloudSpeechSessionParams.EncoderParams.newBuilder()
                        .setEnableEncoder(false)
                ) //.setAllowVbr(true)
                //.setCodec(CodecAndBitrate.OGG_OPUS_BITRATE_32KBPS))
                .build()
        networkChecker = NetworkConnectionChecker(this)
        networkChecker!!.registerNetworkCallback()

        // There are lots of options for formatting the text. These can be useful for debugging
        // and visualization, but it increases the effort of reading the transcripts.
        val formatterOptions =
            TranscriptionResultFormatterOptions.newBuilder()
                .setTranscriptColoringStyle(TranscriptColoringStyle.NO_COLORING)
                .build()
        val recognizerBuilder: RepeatingRecognitionSession.Builder =
            RepeatingRecognitionSession.Companion.newBuilder()
                .setSpeechSessionFactory(
                    CloudSpeechSessionFactory(
                        cloudParams,
                        MainActivity.Companion.getApiKey(this)
                    )
                )
                .setSampleRateHz(MainActivity.Companion.SAMPLE_RATE)
                .setTranscriptionResultFormatter(SafeTranscriptionResultFormatter(formatterOptions))
                .setSpeechRecognitionModelOptions(options)
                .setNetworkConnectionChecker(networkChecker)
        recognizer = recognizerBuilder.build()
        recognizer!!.registerCallback(transcriptUpdater, ResultSource.WHOLE_RESULT)
    }

    private fun startRecording() {
        if (audioRecord == null) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            audioRecord = AudioRecord(
                MainActivity.Companion.MIC_SOURCE,
                MainActivity.Companion.SAMPLE_RATE,
                MainActivity.Companion.MIC_CHANNELS,
                MainActivity.Companion.MIC_CHANNEL_ENCODING,
                MainActivity.Companion.CHUNK_SIZE_SAMPLES * MainActivity.Companion.BYTES_PER_SAMPLE
            )
        }

        audioRecord!!.startRecording()
        Thread(readMicData).start()
    }

    /** The API won't work without a valid API key. This prompts the user to enter one.  */
    private fun showAPIKeyDialog() {
        val contentLayout =
            getLayoutInflater().inflate(R.layout.api_key_message, null) as LinearLayout
        val linkView = contentLayout.findViewById<TextView>(R.id.api_key_link_view)
        linkView.setText(Html.fromHtml(getString(R.string.api_key_doc_link)))
        linkView.setMovementMethod(LinkMovementMethod.getInstance())
        val keyInput = contentLayout.findViewById<EditText>(R.id.api_key_input)
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT)
        keyInput.setText(MainActivity.Companion.getApiKey(this))

        val selectLanguageView = contentLayout.findViewById<TextView>(R.id.language_locale_view)
        selectLanguageView.setText(Html.fromHtml(getString(R.string.select_language_message)))
        selectLanguageView.setMovementMethod(LinkMovementMethod.getInstance())
        val languagesList =
            ArrayAdapter<String?>(
                this,
                android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.languages)
            )
        val sp = contentLayout.findViewById<Spinner>(R.id.language_locale_spinner)
        sp.setAdapter(languagesList)
        sp.setOnItemSelectedListener(
            object : OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    handleLanguageChanged(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            })
        sp.setSelection(currentLanguageCodePosition)

        val builder = AlertDialog.Builder(this)
        builder
            .setTitle(getString(R.string.api_key_message))
            .setView(contentLayout)
            .setPositiveButton(
                getString(android.R.string.ok),
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    MainActivity.Companion.saveApiKey(
                        this,
                        keyInput.getText().toString().trim { it <= ' ' })
                    constructRepeatingRecognitionSession()
                    startRecording()
                })
            .show()
    }

    /** Handles selecting language by spinner.  */
    private fun handleLanguageChanged(itemPosition: Int) {
        currentLanguageCodePosition = itemPosition
        currentLanguageCode = getResources().getStringArray(R.array.language_locales)[itemPosition]
    }

    companion object {
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1

        private val MIC_CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private val MIC_CHANNEL_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private val MIC_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_SAMPLES = 1280
        private const val BYTES_PER_SAMPLE = 2
        private const val SHARE_PREF_API_KEY = "api_key"

        /** Saves the API Key in user shared preference.  */
        private fun saveApiKey(context: Context, key: String?) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(MainActivity.Companion.SHARE_PREF_API_KEY, key)
                .apply()
        }

        /** Gets the API key from shared preference.  */
        private fun getApiKey(context: Context): String {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(MainActivity.Companion.SHARE_PREF_API_KEY, "")!!
        }
    }
}
