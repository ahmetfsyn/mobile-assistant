package com.companion.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly opt-in foreground service. Standby audio is processed locally and never persisted. */
class WakeWordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: WakeWordSettingsStore
    private lateinit var worker: ExecutorService
    @Volatile private var stopRequested = false
    @Volatile private var monitor: WakeWordMonitor? = null
    @Volatile private var monitorTask: Future<*>? = null
    private val audioStartRetryCount = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        settings = WakeWordSettingsStore(applicationContext)
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "AyseLocalWakeWord").apply { isDaemon = true }
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(WakeWordStatus.STARTING))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                settings.setEnabled(false)
                stopMonitoring(WakeWordStatus.DISABLED)
            }
            ACTION_PAUSE -> stopMonitoring(WakeWordStatus.PAUSED)
            ACTION_START, null -> startMonitoring()
            else -> stopMonitoring(WakeWordStatus.PAUSED)
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!settings.isEnabled()) {
            stopMonitoring(WakeWordStatus.DISABLED)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            fail("MICROPHONE_PERMISSION")
            return
        }
        if (monitorTask?.isDone == false) return

        VoskWakeWordModelCache.setRetentionEnabled(true)
        stopRequested = false
        publishStatus(WakeWordStatus.LOADING_MODEL)
        val generation = settings.generation()
        monitorTask = worker.submit {
            val loadStartedAt = SystemClock.elapsedRealtime()
            try {
                val modelDirectory = WakeWordModelInstaller(applicationContext).ensureInstalled()
                if (stopRequested || !settings.isEnabled() || settings.generation() != generation) return@submit
                val engine = VoskWakeWordEngine(modelDirectory)
                val source = try {
                    AudioRecordWakeWordSource()
                } catch (error: Exception) {
                    engine.close()
                    throw error
                }
                val wakeMonitor = WakeWordMonitor(source, engine, onCaptureStarted = { audioStartRetryCount.set(0) })
                monitor = wakeMonitor
                if (stopRequested) {
                    wakeMonitor.close()
                    return@submit
                }
                Log.i(TAG, "model_initialized load_ms=${SystemClock.elapsedRealtime() - loadStartedAt} reused=${engine.reusedModel}")
                publishStatus(WakeWordStatus.LISTENING)
                val listeningStartedAt = SystemClock.elapsedRealtime()
                val detection = wakeMonitor.runUntilDetected()
                monitor = null
                if (detection != null && !stopRequested && settings.isEnabled() && settings.generation() == generation) {
                    val listeningElapsed = SystemClock.elapsedRealtime() - listeningStartedAt
                    settings.setWakeEventPending(generation)
                    settings.setStatus(WakeWordStatus.DETECTED)
                    Log.i(TAG, "wake_detected listening_elapsed_ms=$listeningElapsed command_handoff=${!detection.commandTranscript.isNullOrBlank()}")
                    publishWakeDetected(generation, SystemClock.elapsedRealtime(), detection.commandTranscript)
                } else if (!stopRequested) {
                    fail("MONITOR_STOPPED")
                }
            } catch (error: WakeWordAudioStartException) {
                monitor = null
                if (!stopRequested && settings.isEnabled() && settings.generation() == generation) {
                    scheduleAudioStartRetry(generation, error.reason)
                }
            } catch (error: Exception) {
                monitor = null
                if (!stopRequested) {
                    Log.w(TAG, "monitor_failed type=${error.javaClass.simpleName}")
                    fail(error.javaClass.simpleName)
                }
            } catch (error: LinkageError) {
                monitor = null
                if (!stopRequested) fail(error.javaClass.simpleName)
            } catch (error: OutOfMemoryError) {
                monitor = null
                if (!stopRequested) fail(error.javaClass.simpleName)
            } finally {
                monitor = null
                if (stopRequested || !settings.isEnabled() || settings.status() == WakeWordStatus.DETECTED) {
                    mainHandler.post { finishServiceIfStopped() }
                }
            }
        }
    }

    private fun scheduleAudioStartRetry(generation: Long, reason: String) {
        val attempt = audioStartRetryCount.incrementAndGet()
        if (!WakeWordAudioStartRetryPolicy.shouldRetry(attempt)) {
            audioStartRetryCount.set(0)
            fail("AUDIO_START_RETRIES_EXHAUSTED")
            return
        }
        Log.w(TAG, "audio_start_retry attempt=$attempt delay_ms=$AUDIO_START_RETRY_DELAY_MS reason=$reason")
        publishStatus(WakeWordStatus.STARTING)
        mainHandler.postDelayed({
            if (!stopRequested && settings.isEnabled() && settings.generation() == generation) startMonitoring()
        }, AUDIO_START_RETRY_DELAY_MS)
    }

    private fun fail(reason: String) {
        Log.w(TAG, "monitor_unavailable reason=$reason")
        settings.setEnabled(false)
        settings.setStatus(WakeWordStatus.ERROR)
        VoskWakeWordModelCache.setRetentionEnabled(false)
        publishStatus(WakeWordStatus.ERROR)
        mainHandler.post {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun stopMonitoring(status: WakeWordStatus) {
        stopRequested = true
        monitor?.close()
        if (status == WakeWordStatus.DISABLED) {
            settings.setEnabled(false)
            VoskWakeWordModelCache.setRetentionEnabled(false)
        }
        settings.setStatus(status)
        publishStatus(status)
        mainHandler.post {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun finishServiceIfStopped() {
        if (stopRequested || !settings.isEnabled() || settings.status() == WakeWordStatus.DETECTED) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun publishStatus(status: WakeWordStatus) {
        settings.setStatus(status)
        mainHandler.post {
            if (status == WakeWordStatus.LISTENING || status == WakeWordStatus.LOADING_MODEL ||
                status == WakeWordStatus.STARTING
            ) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(status))
            }
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_STATUS, status.name))
        }
    }

    private fun publishWakeDetected(generation: Long, detectedAtMs: Long, commandTranscript: String?) {
        mainHandler.post {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            val wakeIntent = Intent(ACTION_WAKE_DETECTED).setPackage(packageName)
                .putExtra(EXTRA_GENERATION, generation).putExtra(EXTRA_DETECTED_AT_MS, detectedAtMs)
            commandTranscript?.take(500)?.takeIf(String::isNotBlank)?.let { wakeIntent.putExtra(EXTRA_COMMAND_TRANSCRIPT, it) }
            sendBroadcast(wakeIntent)
            stopForeground(true)
            stopSelf()
        }
    }

    private fun buildNotification(status: WakeWordStatus): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            this, 72, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutablePendingIntentFlag()
        )
        val stopIntent = Intent(this, WakeWordService::class.java).setAction(ACTION_DISABLE)
        val stop = PendingIntent.getService(
            this, 73, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutablePendingIntentFlag()
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Masa Arkadaşı · Ayşe")
            .setContentText(if (status == WakeWordStatus.LOADING_MODEL) "Yerel model hazırlanıyor" else "Ses cihazda işleniyor")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DİNLEMEYİ DURDUR", stop)
            .build()
    }

    private fun immutablePendingIntentFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Yerel uyandırma sözcüğü", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ayşe uyandırma sözcüğü cihazda algılanırken gösterilir"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    override fun onDestroy() {
        stopRequested = true
        monitor?.close()
        monitorTask?.cancel(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.companion.assistant.action.WAKE_WORD_START"
        const val ACTION_DISABLE = "com.companion.assistant.action.WAKE_WORD_DISABLE"
        const val ACTION_PAUSE = "com.companion.assistant.action.WAKE_WORD_PAUSE"
        const val ACTION_STATE_CHANGED = "com.companion.assistant.action.WAKE_WORD_STATE"
        const val ACTION_WAKE_DETECTED = "com.companion.assistant.action.WAKE_WORD_DETECTED"
        const val EXTRA_STATUS = "status"
        const val EXTRA_GENERATION = "monitoring_generation"
        const val EXTRA_DETECTED_AT_MS = "detected_at_elapsed_ms"
        const val EXTRA_COMMAND_TRANSCRIPT = "wake_command_transcript"
        private const val CHANNEL_ID = "companion_wake_word"
        private const val NOTIFICATION_ID = 731
        private const val AUDIO_START_RETRY_DELAY_MS = 350L
        private const val TAG = "WakeWordService"
    }
}

internal object WakeWordServiceControl {
    fun start(context: Context) {
        val intent = Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun pause(context: Context) = stop(context, WakeWordService.ACTION_PAUSE)
    fun disable(context: Context) = stop(context, WakeWordService.ACTION_DISABLE)

    private fun stop(context: Context, action: String) {
        context.startService(Intent(context, WakeWordService::class.java).setAction(action))
    }
}

/** Safely expands the bundled model to private app storage only after the user enables monitoring. */
internal class WakeWordModelInstaller(private val context: Context) {
    @Throws(IOException::class)
    fun ensureInstalled(): File {
        val modelDirectory = File(context.filesDir, "wake-word/vosk-model-small-tr-0.3")
        if (File(modelDirectory, "HCLr.fst").isFile && File(modelDirectory, "final.mdl").isFile) {
            return modelDirectory
        }
        modelDirectory.deleteRecursively()
        if (!modelDirectory.mkdirs() && !modelDirectory.isDirectory) {
            throw IOException("Model storage could not be created")
        }
        val root = modelDirectory.canonicalFile
        context.assets.open(MODEL_ASSET).buffered().use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                val bytes = ByteArray(8_192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Model setup interrupted")
                    val entry = zip.nextEntry ?: break
                    val prefix = "$MODEL_NAME/"
                    val relative = when {
                        entry.name == MODEL_NAME || entry.name == prefix -> ""
                        entry.name.startsWith(prefix) -> entry.name.removePrefix(prefix)
                        else -> throw IOException("Invalid model archive entry")
                    }
                    if (relative.isNotEmpty()) {
                        val output = File(root, relative).canonicalFile
                        if (!output.path.startsWith(root.path + File.separator)) {
                            throw IOException("Invalid model archive path")
                        }
                        if (entry.isDirectory) {
                            if (!output.mkdirs() && !output.isDirectory) throw IOException("Model folder could not be created")
                        } else {
                            output.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("Model folder could not be created") }
                            output.outputStream().buffered().use { target ->
                                while (true) {
                                    val count = zip.read(bytes)
                                    if (count < 0) break
                                    target.write(bytes, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!File(modelDirectory, "HCLr.fst").isFile || !File(modelDirectory, "final.mdl").isFile) {
            modelDirectory.deleteRecursively()
            throw IOException("Bundled Turkish model is incomplete")
        }
        return modelDirectory
    }

    private companion object {
        const val MODEL_NAME = "vosk-model-small-tr-0.3"
        const val MODEL_ASSET = "vosk-model-small-tr-0.3.zip"
    }
}

/** Keeps the native Vosk model warm between wake sessions while monitoring remains opted in. */
internal object VoskWakeWordModelCache {
    private var model: Model? = null
    private var modelPath: String? = null
    private var activeLeases = 0
    private var retentionEnabled = false

    @Synchronized
    fun setRetentionEnabled(enabled: Boolean) {
        retentionEnabled = enabled
        releaseIfUnused()
    }

    @Synchronized
    fun acquire(modelDirectory: File): Lease {
        val path = modelDirectory.canonicalPath
        var current = model
        val reused = current != null && modelPath == path
        if (current != null && modelPath != path) {
            check(activeLeases == 0) { "Cannot replace a Vosk model while it is in use" }
            runCatching { current.close() }
            model = null
            modelPath = null
            current = null
        }
        val loaded = current ?: Model(path).also {
            model = it
            modelPath = path
        }
        activeLeases++
        return Lease(loaded, reused, ::release)
    }

    @Synchronized
    private fun release() {
        if (activeLeases > 0) activeLeases--
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (retentionEnabled || activeLeases != 0) return
        runCatching { model?.close() }
        model = null
        modelPath = null
    }

    class Lease internal constructor(
        val model: Model,
        val reused: Boolean,
        private val releaseModel: () -> Unit
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) releaseModel()
        }
    }
}

/** Grammar-constrained local wake decoder with a one-shot full-utterance command handoff. */
internal class VoskWakeWordEngine(modelDirectory: File) : WakeWordEngine {
    private val modelLease: VoskWakeWordModelCache.Lease
    private val model: Model
    private val wakeRecognizer: Recognizer
    private val closed = AtomicBoolean(false)
    private val utteranceAudio = WipingAudioBuffer(MAX_UTTERANCE_BYTES)

    val reusedModel: Boolean
        get() = modelLease.reused

    init {
        val lease = VoskWakeWordModelCache.acquire(modelDirectory)
        modelLease = lease
        model = lease.model
        wakeRecognizer = try {
            Recognizer(model, WakeWordMonitor.SAMPLE_RATE_HZ.toFloat(), WAKE_GRAMMAR).apply {
                setWords(true)
                // Keep a short pause for a command after “Ayşe” while reducing endpoint delay.
                setEndpointerDelays(ENDPOINTER_INITIAL_SILENCE_SECONDS, ENDPOINTER_END_SILENCE_SECONDS, ENDPOINTER_MAX_SECONDS)
            }
        } catch (error: Throwable) {
            modelLease.close()
            throw error
        }
    }

    override fun acceptAudio(pcm16le: ByteArray, length: Int): String? {
        if (closed.get() || length <= 0 || length > pcm16le.size) return null
        if (utteranceAudio.size() + length > MAX_UTTERANCE_BYTES) {
            utteranceAudio.erase()
            Log.w(TAG, "wake_utterance_buffer_cleared reason=duration_limit")
        }
        utteranceAudio.write(pcm16le, length)
        if (!wakeRecognizer.acceptWaveForm(pcm16le, length)) return null

        val result = JSONObject(wakeRecognizer.result)
        val wakeText = result.optString("text").trim()
        val confidence = result.optJSONArray("result")?.optJSONObject(0)?.optDouble("conf", 0.0) ?: 0.0
        if (WakeWordMatcher.match(wakeText) == null || !WakeWordConfidencePolicy.accepts(confidence)) {
            if (WakeWordMatcher.match(wakeText) != null) {
                Log.i(TAG, "wake_candidate_rejected confidence=$confidence")
            }
            utteranceAudio.erase()
            return null
        }

        val audio = utteranceAudio.toByteArray()
        utteranceAudio.erase()
        val audioDurationMs = audio.size * 1_000L / (WakeWordMonitor.SAMPLE_RATE_HZ * 2L)
        val wakeAudio = WakeWordAudioWindow.trailing(audio)
        val wakeAudioDurationMs = wakeAudio.size * 1_000L / (WakeWordMonitor.SAMPLE_RATE_HZ * 2L)
        val shortConfirmationStartedAt = SystemClock.elapsedRealtime()
        val wakeConfirmation = try {
            decodeCommandFromSameUtterance(wakeAudio)
        } finally {
            wakeAudio.fill(0)
        }
        val shortConfirmationDurationMs = SystemClock.elapsedRealtime() - shortConfirmationStartedAt
        Log.i(TAG, "wake_word_confirmation duration_ms=$shortConfirmationDurationMs audio_ms=$wakeAudioDurationMs exact=${wakeConfirmation.exactWakeWord} similar_name=${wakeConfirmation.similarName}")
        if (wakeConfirmation.similarName) {
            Log.i(TAG, "wake_candidate_rejected reason=similar_name confidence=$confidence")
            audio.fill(0)
            return null
        }
        if (!wakeConfirmation.exactWakeWord) {
            Log.i(TAG, "wake_candidate_rejected reason=word_not_exactly_confirmed confidence=$confidence")
            audio.fill(0)
            return null
        }

        if (WakeWordFastPathPolicy.isShortUtterance(audioDurationMs)) {
            Log.i(TAG, "wake_candidate_accepted confidence=$confidence command_handoff=false")
            audio.fill(0)
            return "Ayşe"
        }

        val confirmationStartedAt = SystemClock.elapsedRealtime()
        val commandDecode = try {
            decodeCommandFromSameUtterance(audio)
        } finally {
            audio.fill(0)
        }
        val confirmationDurationMs = SystemClock.elapsedRealtime() - confirmationStartedAt
        Log.i(TAG, "wake_command_decode duration_ms=$confirmationDurationMs audio_ms=$audioDurationMs exact=${commandDecode.exactWakeWord} similar_name=${commandDecode.similarName} command_handoff=${!commandDecode.transcript.isNullOrBlank()}")
        if (commandDecode.similarName) {
            Log.i(TAG, "wake_candidate_rejected reason=similar_name confidence=$confidence")
            return null
        }
        val commandTranscript = commandDecode.transcript
        if (commandTranscript.isNullOrBlank() && !commandDecode.exactWakeWord) {
            Log.i(TAG, "wake_candidate_rejected reason=not_exactly_confirmed confidence=$confidence")
            return null
        }
        Log.i(TAG, "wake_candidate_accepted confidence=$confidence command_handoff=${!commandTranscript.isNullOrBlank()}")
        return commandTranscript ?: "Ayşe"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        utteranceAudio.erase()
        try { wakeRecognizer.close() } finally { modelLease.close() }
    }

    private fun decodeCommandFromSameUtterance(audio: ByteArray): CommandDecode {
        if (audio.isEmpty()) return CommandDecode()
        val decoder = Recognizer(model, WakeWordMonitor.SAMPLE_RATE_HZ.toFloat()).apply { setWords(true) }
        return try {
            val ended = decoder.acceptWaveForm(audio, audio.size)
            val resultText = if (ended) {
                JSONObject(decoder.result).optString("text").trim()
            } else {
                JSONObject(decoder.finalResult).optString("text").trim()
            }
            if (WakeWordMatcher.isKnownConfusableStart(resultText)) return CommandDecode(similarName = true)
            val match = WakeWordMatcher.match(resultText) ?: return CommandDecode()
            if (match.commandTranscript?.contains("[unk]") == true) return CommandDecode()
            if (match.commandTranscript == null) CommandDecode(exactWakeWord = true) else CommandDecode(resultText)
        } catch (error: RuntimeException) {
            Log.w(TAG, "wake_command_handoff_failed type=${error.javaClass.simpleName}")
            CommandDecode()
        } finally {
            decoder.close()
        }
    }

    private data class CommandDecode(
        val transcript: String? = null,
        val similarName: Boolean = false,
        val exactWakeWord: Boolean = false
    )

    private companion object {
        const val TAG = "WakeWordService"
        const val WAKE_GRAMMAR = "[\"ayşe\",\"aylin\",\"ayşegül\",\"ayşe'nin\",\"[unk]\"]"
        // Flush empty silence more often so command confirmation does not decode seconds of idle audio.
        const val ENDPOINTER_INITIAL_SILENCE_SECONDS = 1.5f
        const val ENDPOINTER_END_SILENCE_SECONDS = 0.4f
        const val ENDPOINTER_MAX_SECONDS = 20f
        const val MAX_UTTERANCE_BYTES = WakeWordMonitor.SAMPLE_RATE_HZ * 2 * 15
    }

    private class WipingAudioBuffer(private val maxBytes: Int) : ByteArrayOutputStream() {
        fun write(source: ByteArray, length: Int) {
            val acceptedLength = length.coerceAtMost(maxBytes)
            write(source, 0, acceptedLength)
        }

        fun erase() {
            buf.fill(0)
            reset()
        }
    }
}

/** Single reusable 16 kHz mono PCM16 recorder. No file or network operation receives its buffer. */
internal class AudioRecordWakeWordSource : WakeWordAudioSource {
    private val closed = AtomicBoolean(false)
    private val audioRecord: AudioRecord

    init {
        val minBytes = AudioRecord.getMinBufferSize(
            WakeWordMonitor.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) throw IllegalStateException("Unsupported microphone format")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WakeWordMonitor.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes * 2, WakeWordMonitor.DEFAULT_BUFFER_BYTES)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }
    }

    override fun start(): Boolean {
        if (closed.get()) return false
        return try {
            audioRecord.startRecording()
            audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) {
            Log.w("WakeWordService", "audio_record_start_rejected reason=illegal_state")
            false
        }
    }

    override fun read(buffer: ByteArray): Int {
        if (closed.get()) return -1
        return audioRecord.read(buffer, 0, buffer.size)
    }

    override fun stop() {
        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
        } catch (_: IllegalStateException) { }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        audioRecord.release()
    }
}
