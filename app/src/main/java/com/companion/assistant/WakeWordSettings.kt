package com.companion.assistant

import android.content.Context

internal enum class WakeWordStatus(val label: String) {
    DISABLED("Kapalı"),
    STARTING("Dinleme başlatılıyor"),
    LOADING_MODEL("Yerel model hazırlanıyor"),
    LISTENING("Yerel olarak dinliyor: Ayşe"),
    PAUSED("Dinleme duraklatıldı"),
    DETECTED("Ayşe algılandı · mikrofon duraklatıldı"),
    ERROR("Dinleme başlatılamadı")
}

internal class WakeWordSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)
    fun generation(): Long = preferences.getLong(KEY_GENERATION, 0L)
    fun setEnabled(enabled: Boolean) {
        val edit = preferences.edit().putLong(KEY_PENDING_GENERATION, NO_PENDING_WAKE)
        if (isEnabled() != enabled) edit.putLong(KEY_GENERATION, generation() + 1L)
        edit.putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun status(): WakeWordStatus = runCatching {
        WakeWordStatus.valueOf(preferences.getString(KEY_STATUS, WakeWordStatus.DISABLED.name).orEmpty())
    }.getOrDefault(WakeWordStatus.DISABLED)

    fun setStatus(status: WakeWordStatus) {
        preferences.edit().putString(KEY_STATUS, status.name).apply()
    }

    fun setWakeEventPending(generation: Long) {
        preferences.edit().putLong(KEY_PENDING_GENERATION, generation).apply()
    }

    @Synchronized
    fun consumeWakeEventPending(): Long? {
        val pending = preferences.getLong(KEY_PENDING_GENERATION, NO_PENDING_WAKE)
        if (pending != NO_PENDING_WAKE) preferences.edit().putLong(KEY_PENDING_GENERATION, NO_PENDING_WAKE).apply()
        return pending.takeUnless { it == NO_PENDING_WAKE }
    }

    private companion object {
        const val PREFERENCES = "wake_word_settings"
        const val KEY_ENABLED = "monitoring_enabled"
        const val KEY_STATUS = "monitoring_status"
        const val KEY_GENERATION = "monitoring_generation"
        const val KEY_PENDING_GENERATION = "wake_event_pending_generation"
        const val NO_PENDING_WAKE = -1L
    }
}
