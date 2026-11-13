package com.companion.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Explicitly entered family details. No audio or inferred identity is stored. */
internal data class FamilyProfile(
    val id: String,
    val displayName: String,
    val relationship: String = "",
    val preferredAddress: String = "",
    val preferences: String = ""
)

internal object FamilyProfileRules {
    const val MAX_PROFILES = 12
    fun save(profiles: List<FamilyProfile>, profile: FamilyProfile): List<FamilyProfile> {
        require(profile.displayName.trim().isNotEmpty()) { "İsim gerekli" }
        require(profile.displayName.length <= 60 && profile.relationship.length <= 60 &&
            profile.preferredAddress.length <= 60 && profile.preferences.length <= 200) { "Alan çok uzun" }
        if (profiles.none { it.id == profile.id } && profiles.size >= MAX_PROFILES) throw IllegalStateException("Profil sınırına ulaşıldı")
        return profiles.filterNot { it.id == profile.id } + profile
    }

    fun delete(profiles: List<FamilyProfile>, id: String): List<FamilyProfile> = profiles.filterNot { it.id == id }
}

internal sealed class SpeakerIdentity {
    data object Unknown : SpeakerIdentity()
    data class Verified(val profileId: String, val confidence: Float) : SpeakerIdentity()
}

internal object SpeakerIdentityPolicy {
    /** No local embedding model has been validated on this device; every acoustic turn is unknown. */
    fun current(): SpeakerIdentity = SpeakerIdentity.Unknown
}

internal class FamilyProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("family_profiles", Context.MODE_PRIVATE)

    fun list(): List<FamilyProfile> = runCatching {
        val json = JSONArray(preferences.getString("profiles", "[]"))
        (0 until json.length()).mapNotNull { index ->
            val entry = json.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            val name = entry.optString("name")
            if (id.isBlank() || name.isBlank()) null else FamilyProfile(id, name,
                entry.optString("relationship"), entry.optString("address"), entry.optString("preferences"))
        }.take(FamilyProfileRules.MAX_PROFILES)
    }.getOrDefault(emptyList())

    fun save(profile: FamilyProfile) = write(FamilyProfileRules.save(list(), profile))
    fun delete(id: String) = write(FamilyProfileRules.delete(list(), id))

    private fun write(profiles: List<FamilyProfile>) {
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(JSONObject().put("id", profile.id).put("name", profile.displayName.trim())
                .put("relationship", profile.relationship.trim()).put("address", profile.preferredAddress.trim())
                .put("preferences", profile.preferences.trim()))
        }
        preferences.edit().putString("profiles", json.toString()).apply()
    }
}

internal object FamilyProfileDialogs {
    fun show(activity: Activity) {
        val store = FamilyProfileStore(activity)
        val profiles = store.list()
        val items = profiles.map { it.displayName + if (it.relationship.isBlank()) "" else " · ${it.relationship}" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("AİLE PROFİLLERİ")
            .setMessage(if (items.isEmpty()) "Henüz profil yok. Sesle kişi tanıma doğrulanmadığı için konuşan kişi bilinmiyor." else null)
            .setItems(items) { _, index -> edit(activity, store, profiles[index]) }
            .setPositiveButton("PROFİL EKLE") { _, _ -> edit(activity, store, null) }
            .setNegativeButton("KAPAT", null).show()
    }

    private fun edit(activity: Activity, store: FamilyProfileStore, existing: FamilyProfile?) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (18 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun field(hint: String, value: String): EditText = EditText(activity).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            panel.addView(this)
        }
        val name = field("Adı", existing?.displayName.orEmpty())
        val relationship = field("Yakınlık (isteğe bağlı)", existing?.relationship.orEmpty())
        val address = field("Hitap şekli (isteğe bağlı)", existing?.preferredAddress.orEmpty())
        val preferences = field("Açıkça belirtilen tercihler (isteğe bağlı)", existing?.preferences.orEmpty())
        panel.addView(TextView(activity).apply {
            text = "Ses kaydı ve otomatik kişi tanıma henüz mevcut değil. Bu profil yalnızca senin girdiğin bilgileri saklar."
        })
        val builder = AlertDialog.Builder(activity).setTitle(if (existing == null) "PROFİL EKLE" else "PROFİLİ DÜZENLE")
            .setView(ScrollView(activity).apply { addView(panel) })
            .setPositiveButton("KAYDET") { _, _ ->
                val profile = FamilyProfile(existing?.id ?: UUID.randomUUID().toString(),
                    name.text.toString(), relationship.text.toString(), address.text.toString(), preferences.text.toString())
                runCatching { store.save(profile) }.onFailure {
                    android.widget.Toast.makeText(activity, it.message ?: "Profil kaydedilemedi", android.widget.Toast.LENGTH_LONG).show()
                }
                show(activity)
            }
            .setNegativeButton("KAPAT", null)
        if (existing != null) builder.setNeutralButton("SİL") { _, _ ->
            AlertDialog.Builder(activity).setMessage("${existing.displayName} profilini silmek istiyor musun?")
                .setPositiveButton("SİL") { _, _ -> store.delete(existing.id); show(activity) }
                .setNegativeButton("VAZGEÇ", null).show()
        }
        builder.show()
    }
}
