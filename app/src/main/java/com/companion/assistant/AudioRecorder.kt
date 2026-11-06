package com.companion.assistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlin.math.sqrt

/** One in-memory mono PCM recording. Closing it overwrites the audio bytes. */
internal class AudioRecording(
    val pcm16le: ByteArray,
    val sampleRate: Int
) : Closeable {
    override fun close() {
        pcm16le.fill(0)
    }
}

/** Push-to-talk recorder that stops after sustained silence and never writes audio to disk. */
internal class AudioRecorder {
    interface Callback {
        fun onRecordingComplete(recording: AudioRecording)
        fun onRecordingError(message: String)
    }

    private val lock = Any()
    private var sequence = 0L

    @Volatile
    private var activeSession = 0L

    @Volatile
    private var activeRecorder: AudioRecord? = null

    private var recordingThread: Thread? = null

    fun start(callback: Callback): Boolean = synchronized(lock) {
        if (activeSession != 0L || recordingThread?.isAlive == true) return@synchronized false

        val session = ++sequence
        activeSession = session
        val thread = Thread({ record(session, callback) }, "CompanionAudioRecorder")
        thread.isDaemon = true
        recordingThread = thread
        thread.start()
        true
    }

    fun cancel() {
        val recorder = synchronized(lock) {
            activeSession = 0L
            activeRecorder
        }
        if (recorder != null) {
            Thread({
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                } catch (_: IllegalStateException) {
                    // The recording thread owns release and may be exiting already.
                }
            }, "CompanionAudioStop").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun record(session: Long, callback: Callback) {
        val output = WipingByteArrayOutputStream(INITIAL_CAPACITY)
        var recorder: AudioRecord? = null
        var completedRecording: AudioRecording? = null
        var errorMessage: String? = null
        var shouldNotify = false

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw RecordingFailure("SES KAYDI BAŞLATILAMADI")

            val bufferSize = maxOf(minBuffer, MIN_BUFFER_SIZE)
            val createdRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            recorder = createdRecorder
            if (createdRecorder.state != AudioRecord.STATE_INITIALIZED) {
                throw RecordingFailure("MİKROFON BAŞLATILAMADI")
            }

            synchronized(lock) {
                if (activeSession != session) return
                activeRecorder = createdRecorder
            }
            createdRecorder.startRecording()
            if (createdRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw RecordingFailure("MİKROFON BAŞLATILAMADI")
            }

            val samples = ShortArray(bufferSize / 2)
            val bytes = ByteArray(samples.size * 2)
            val startTime = SystemClock.elapsedRealtime()
            var speechStarted = false
            var silenceStarted = 0L

            while (isSessionActive(session)) {
                val now = SystemClock.elapsedRealtime()
                if (now - startTime >= MAX_RECORDING_MS) break

                val count = createdRecorder.read(samples, 0, samples.size)
                if (count < 0) throw RecordingFailure("MİKROFON KAYDI KESİLDİ")
                if (count == 0) continue

                var squareSum = 0L
                for (i in 0 until count) {
                    val sample = samples[i].toInt()
                    squareSum += sample.toLong() * sample.toLong()
                    bytes[i * 2] = (sample and 0xff).toByte()
                    bytes[i * 2 + 1] = (sample shr 8).toByte()
                }
                output.write(bytes, 0, count * 2)

                val rms = sqrt(squareSum.toDouble() / count).toInt()
                val chunkTime = SystemClock.elapsedRealtime()
                if (rms >= SPEECH_THRESHOLD_RMS) {
                    speechStarted = true
                    silenceStarted = 0L
                } else if (speechStarted) {
                    if (silenceStarted == 0L) silenceStarted = chunkTime
                    if (chunkTime - silenceStarted >= END_OF_SPEECH_SILENCE_MS) break
                } else if (chunkTime - startTime >= NO_SPEECH_TIMEOUT_MS) {
                    throw RecordingFailure("SES ALGILANAMADI")
                }
            }

            if (isSessionActive(session)) {
                if (!speechStarted) {
                    errorMessage = "SES ALGILANAMADI"
                } else {
                    completedRecording = AudioRecording(output.toByteArray(), SAMPLE_RATE)
                }
                shouldNotify = true
            }
        } catch (error: SecurityException) {
            if (isSessionActive(session)) {
                errorMessage = "MİKROFON İZNİ GEREKLİ"
                shouldNotify = true
            }
        } catch (error: RecordingFailure) {
            if (isSessionActive(session)) {
                errorMessage = error.message
                shouldNotify = true
            }
        } catch (_: Exception) {
            if (isSessionActive(session)) {
                errorMessage = "MİKROFON KULLANILAMIYOR"
                shouldNotify = true
            }
        } finally {
            output.erase()
            if (recorder != null) {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                } catch (_: IllegalStateException) {
                    // Release still runs if stop races with a platform recording error.
                }
                recorder.release()
            }
            synchronized(lock) {
                if (activeRecorder === recorder) activeRecorder = null
                if (activeSession == session) activeSession = 0L
                if (recordingThread === Thread.currentThread()) recordingThread = null
            }
        }

        if (!shouldNotify) {
            completedRecording?.close()
            return
        }
        val completed = completedRecording
        if (completed != null) callback.onRecordingComplete(completed)
        else callback.onRecordingError(errorMessage ?: "MİKROFON HATASI")
    }

    private fun isSessionActive(session: Long): Boolean = activeSession == session

    private class WipingByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun erase() {
            buf.fill(0)
            reset()
        }
    }

    private class RecordingFailure(message: String) : Exception(message)

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_BUFFER_SIZE = 4096
        const val INITIAL_CAPACITY = SAMPLE_RATE * 2 * 2
        const val MAX_RECORDING_MS = 15_000L
        const val NO_SPEECH_TIMEOUT_MS = 8_000L
        const val END_OF_SPEECH_SILENCE_MS = 900L
        const val SPEECH_THRESHOLD_RMS = 540
    }
}
