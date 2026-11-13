package com.companion.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal enum class WeatherQueryKind { CURRENT, TODAY_RANGE, TODAY_RAIN, TOMORROW, TOMORROW_RAIN, WEEKEND }

internal data class WeatherQuery(val kind: WeatherQueryKind, val explicitCity: String? = null)

internal object WeatherQueryParser {
    private val turkish = Locale("tr", "TR")
    private val citySuffix = Regex("([\\p{L}]+(?:[ -]+[\\p{L}]+){0,2})['’]?(?:da|de|ta|te)\\b", RegexOption.IGNORE_CASE)
    private val locationFillers = setOf("ayşe", "hava", "bugün", "yarın", "bugun", "yarin", "yağmur", "durumu", "nasıl", "kaç", "derece")

    fun parse(transcript: String): WeatherQuery? {
        val text = transcript.lowercase(turkish).replace(Regex("[’‘ʼ´`]"), "'")
            .replace(Regex("\\s+"), " ").trim()
        val asksWeather = listOf("hava", "yağmur", "yağış", "yağis", "sıcaklık", "sicaklik", "derece").any(text::contains)
        if (!asksWeather) return null
        val city = citySuffix.findAll(text).lastOrNull()?.groupValues?.get(1)
            ?.split(Regex("[ -]+"))
            ?.filterNot { it in locationFillers }
            ?.joinToString(" ")
            ?.takeIf { it.length >= 2 }
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(turkish) else it.toString() }
        val kind = when {
            "hafta sonu" in text || "haftasonu" in text -> WeatherQueryKind.WEEKEND
            "yarın" in text || "yarin" in text -> if ("yağmur" in text || "yağış" in text || "yağis" in text) {
                WeatherQueryKind.TOMORROW_RAIN
            } else WeatherQueryKind.TOMORROW
            "yağmur" in text || "yağış" in text || "yağis" in text -> WeatherQueryKind.TODAY_RAIN
            "en yüksek" in text || "en düşük" in text || "kaç derece" in text || "kac derece" in text ->
                WeatherQueryKind.TODAY_RANGE
            else -> WeatherQueryKind.CURRENT
        }
        return WeatherQuery(kind, city)
    }
}

internal data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double) {
    val cacheKey: String get() = String.format(Locale.US, "%.2f,%.2f", latitude, longitude)
}

internal data class WeatherDailyForecast(
    val date: String,
    val weatherCode: Int,
    val minimumCelsius: Double?,
    val maximumCelsius: Double?,
    val precipitationProbabilityPercent: Int?
)

internal data class WeatherForecast(
    val locationName: String,
    val timeZone: String,
    val currentTemperatureCelsius: Double?,
    val apparentTemperatureCelsius: Double?,
    val currentWeatherCode: Int?,
    val windSpeedKmh: Double?,
    val days: List<WeatherDailyForecast>,
    val retrievedAtMillis: Long
)

internal interface WeatherForecastApi {
    @Throws(IOException::class)
    fun locate(city: String): WeatherLocation

    @Throws(IOException::class)
    fun forecast(location: WeatherLocation): WeatherForecast
}

internal interface WeatherForecastCache {
    fun read(key: String): WeatherForecast?
    fun write(key: String, forecast: WeatherForecast)
}

internal interface PreferredWeatherCityStore {
    fun get(): String?
    fun set(city: String?)
}

internal interface WeatherLocationSource {
    fun hasPermission(): Boolean
    fun lastKnownLocation(): WeatherLocation?
}

internal fun interface WeatherDiagnosticLogger {
    fun warning(message: String)
}

private val androidWeatherLogger = WeatherDiagnosticLogger { Log.w("WeatherService", it) }

internal enum class WeatherPermissionDecision { NOT_REQUESTED, GRANTED, DENIED }

internal sealed class WeatherCommandResult {
    data object NotHandled : WeatherCommandResult()
    data object NeedsLocationPermission : WeatherCommandResult()
    data class Handled(val message: String) : WeatherCommandResult()
}

internal class WeatherCommandProcessor(
    private val api: WeatherForecastApi,
    private val cache: WeatherForecastCache,
    private val preferredCityStore: PreferredWeatherCityStore,
    private val locationSource: WeatherLocationSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val logger: WeatherDiagnosticLogger = androidWeatherLogger
) {
    fun process(transcript: String, permissionDecision: WeatherPermissionDecision = WeatherPermissionDecision.NOT_REQUESTED): WeatherCommandResult {
        val query = WeatherQueryParser.parse(transcript) ?: return WeatherCommandResult.NotHandled
        val city = query.explicitCity ?: preferredCityStore.get()?.takeIf(String::isNotBlank)
        val cacheKeyHint = city?.let(::cityCacheKey)

        val location = if (city != null) {
            try {
                api.locate(city)
            } catch (error: IOException) {
                return cachedOrUnavailable(cacheKeyHint!!, query, error)
            }
        } else {
            if (!locationSource.hasPermission()) {
                return if (permissionDecision == WeatherPermissionDecision.NOT_REQUESTED) {
                    WeatherCommandResult.NeedsLocationPermission
                } else {
                    WeatherCommandResult.Handled("Hava durumuna bakmam için bir şehir adı söyleyebilir veya Ayarlar'dan konum izni verebilirsin.")
                }
            }
            locationSource.lastKnownLocation() ?: return WeatherCommandResult.Handled(
                "Yakınında kayıtlı bir konum bulamadım. Hangi şehir için hava durumuna bakayım?"
            )
        }

        val cacheKey = cacheKeyHint ?: location.cacheKey
        return try {
            val fresh = api.forecast(location)
            cache.write(cacheKey, fresh)
            WeatherCommandResult.Handled(WeatherReplyFormatter.format(query, fresh))
        } catch (error: IOException) {
            cachedOrUnavailable(cacheKey, query, error)
        }
    }

    private fun cachedOrUnavailable(key: String, query: WeatherQuery, error: IOException): WeatherCommandResult {
        val cached = cache.read(key)
        val age = cached?.let { nowMillis() - it.retrievedAtMillis }
        val maxAge = if (query.kind == WeatherQueryKind.CURRENT) MAX_CURRENT_CACHE_AGE_MS else MAX_FORECAST_CACHE_AGE_MS
        if (cached != null && age != null && age in 0..maxAge) {
            val staleReply = WeatherReplyFormatter.format(query, cached)
            val time = java.text.SimpleDateFormat("HH:mm", Locale("tr", "TR")).format(cached.retrievedAtMillis)
            logger.warning("weather_cache_used age_ms=$age type=${error.javaClass.simpleName}")
            return WeatherCommandResult.Handled("Canlı veriye ulaşamadım. Saat $time'de alınan bilgiye göre: $staleReply")
        }
        logger.warning("weather_unavailable cached=${cached != null} type=${error.javaClass.simpleName}")
        val stale = if (cached != null) "Önceden alınmış hava bilgisi eski olduğu için kullanmadım. " else ""
        return WeatherCommandResult.Handled("${stale}Şu anda güncel hava verisine ulaşamıyorum. İnternet bağlantını kontrol edip tekrar deneyebilirsin.")
    }

    private fun cityCacheKey(city: String): String = "city:${city.lowercase(Locale("tr", "TR")).trim()}"

    private companion object {
        const val MAX_CURRENT_CACHE_AGE_MS = 3 * 60 * 60 * 1_000L
        const val MAX_FORECAST_CACHE_AGE_MS = 24 * 60 * 60 * 1_000L
    }
}

internal object WeatherReplyFormatter {
    fun format(query: WeatherQuery, forecast: WeatherForecast): String {
        val place = if (forecast.locationName == "Bulunduğun yerde") {
            "Bulunduğun yerde"
        } else {
            "${forecast.locationName}${TurkishLocative.suffix(forecast.locationName)}"
        }
        val today = forecast.days.firstOrNull()
        val tomorrow = forecast.days.getOrNull(1)
        return when (query.kind) {
            WeatherQueryKind.CURRENT -> {
                val temperature = forecast.currentTemperatureCelsius?.let(::degrees) ?: ""
                val description = forecast.currentWeatherCode?.let(WeatherCodeDescription::inTurkish).orEmpty()
                if (temperature.isEmpty() && description.isEmpty()) "Hava bilgisi şu an alınamadı."
                else {
                    val feelsLike = forecast.apparentTemperatureCelsius?.takeIf {
                        temperature.isNotEmpty() && kotlin.math.abs(it - (forecast.currentTemperatureCelsius ?: it)) >= 1.0
                    }?.let { ", hissedilen ${degrees(it)} derece" }.orEmpty()
                    val conditions = listOfNotNull(
                        temperature.takeIf(String::isNotEmpty)?.let { "$it derece$feelsLike" },
                        description.takeIf(String::isNotEmpty)
                    ).joinToString(" ve ")
                    "$place hava $conditions."
                }
            }
            WeatherQueryKind.TODAY_RANGE -> today?.let {
                "Bugün $place en yüksek ${it.maximumCelsius?.let(::degrees) ?: "bilinmiyor"}, en düşük ${it.minimumCelsius?.let(::degrees) ?: "bilinmiyor"} derece."
            } ?: "Bugünün tahmini bulunamadı."
            WeatherQueryKind.TODAY_RAIN -> today?.precipitationProbabilityPercent?.let {
                "Bugün $place yağmur ihtimali yüzde $it."
            } ?: "Bugün için yağmur olasılığı bilgisi bulunamadı."
            WeatherQueryKind.TOMORROW -> tomorrow?.let {
                val condition = WeatherCodeDescription.inTurkish(it.weatherCode)
                "Yarın $place $condition, sıcaklık ${it.minimumCelsius?.let(::degrees) ?: "bilinmiyor"} ile ${it.maximumCelsius?.let(::degrees) ?: "bilinmiyor"} derece arası."
            } ?: "Yarının tahmini bulunamadı."
            WeatherQueryKind.TOMORROW_RAIN -> tomorrow?.precipitationProbabilityPercent?.let {
                "Yarın $place yağmur ihtimali yüzde $it."
            } ?: "Yarın için yağmur olasılığı bilgisi bulunamadı."
            WeatherQueryKind.WEEKEND -> {
                val zone = java.util.TimeZone.getTimeZone(forecast.timeZone)
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
                val weekend = forecast.days.drop(1).take(6).filter { day ->
                    runCatching {
                        val date = dateFormat.parse(day.date) ?: return@runCatching false
                        val dayOfWeek = java.util.Calendar.getInstance(zone).apply { time = date }.get(java.util.Calendar.DAY_OF_WEEK)
                        dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
                    }.getOrDefault(false)
                }
                if (weekend.isEmpty()) "Hafta sonu tahmini henüz bulunamadı."
                else {
                    val highs = weekend.mapNotNull(WeatherDailyForecast::maximumCelsius)
                    val low = weekend.mapNotNull(WeatherDailyForecast::minimumCelsius).minOrNull()
                    val high = highs.maxOrNull()
                    "Hafta sonu $place sıcaklık ${low?.let(::degrees) ?: "bilinmiyor"} ile ${high?.let(::degrees) ?: "bilinmiyor"} derece arasında."
                }
            }
        }
    }

    private fun degrees(value: Double): String = String.format(Locale("tr", "TR"), "%.0f", value)
}

internal object TurkishLocative {
    private const val VOWELS = "aıoueiöü"
    private const val FRONT_VOWELS = "eiöü"
    private const val UNVOICED = "çfhkpsşt"

    fun suffix(place: String): String {
        val lastVowel = place.lowercase(Locale("tr", "TR")).lastOrNull { it in VOWELS } ?: 'a'
        val lastChar = place.lowercase(Locale("tr", "TR")).lastOrNull() ?: ' '
        val vowelPart = if (lastVowel in FRONT_VOWELS) "e" else "a"
        val consonantPart = if (lastChar in UNVOICED) "t" else "d"
        return "'$consonantPart$vowelPart"
    }
}

internal object WeatherCodeDescription {
    fun inTurkish(code: Int): String = when (code) {
        0 -> "açık"
        1 -> "az bulutlu"
        2 -> "parçalı bulutlu"
        3 -> "kapalı"
        45, 48 -> "sisli"
        51, 53, 55, 56, 57 -> "çisenti"
        61, 63, 65, 66, 67 -> "yağmurlu"
        71, 73, 75, 77 -> "karlı"
        80, 81, 82 -> "sağanak yağışlı"
        85, 86 -> "kar sağanaklı"
        95, 96, 99 -> "gök gürültülü sağanaklı"
        else -> "hava durumu değişken"
    }
}

/** Open-Meteo public forecast and geocoding APIs. No key or paid provider is required. */
internal class OpenMeteoWeatherApi(
    private val http: WeatherHttpTransport = UrlConnectionWeatherTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) : WeatherForecastApi {
    override fun locate(city: String): WeatherLocation {
        val encodedCity = URLEncoder.encode(city, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=5&language=tr&format=json"
        val root = parseJson(http.get(url))
        val results = root.optJSONArray("results") ?: throw WeatherProviderFailure("WEATHER_LOCATION_NOT_FOUND")
        val choices = (0 until results.length()).mapNotNull(results::optJSONObject)
        val chosen = choices.firstOrNull { it.optString("country_code").equals("TR", ignoreCase = true) }
            ?: choices.firstOrNull()
            ?: throw WeatherProviderFailure("WEATHER_LOCATION_NOT_FOUND")
        val latitude = chosen.optDouble("latitude", Double.NaN)
        val longitude = chosen.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) throw WeatherProviderFailure("WEATHER_LOCATION_INVALID")
        val name = chosen.optString("name", city).ifBlank { city }
        return WeatherLocation(name, latitude, longitude)
    }

    override fun forecast(location: WeatherLocation): WeatherForecast {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7&temperature_unit=celsius&wind_speed_unit=kmh"
        val root = parseJson(http.get(url))
        val current = root.optJSONObject("current") ?: throw WeatherProviderFailure("WEATHER_CURRENT_MISSING")
        val daily = root.optJSONObject("daily") ?: throw WeatherProviderFailure("WEATHER_DAILY_MISSING")
        val dates = daily.optJSONArray("time") ?: throw WeatherProviderFailure("WEATHER_DATES_MISSING")
        val codes = daily.optJSONArray("weather_code")
        val maximums = daily.optJSONArray("temperature_2m_max")
        val minimums = daily.optJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val days = (0 until dates.length()).map { index ->
            WeatherDailyForecast(
                date = dates.optString(index),
                weatherCode = codes?.optInt(index, -1)?.takeIf { it >= 0 } ?: -1,
                minimumCelsius = minimums?.optDouble(index, Double.NaN)?.takeIf(Double::isFinite),
                maximumCelsius = maximums?.optDouble(index, Double.NaN)?.takeIf(Double::isFinite),
                precipitationProbabilityPercent = rain?.optInt(index, -1)?.takeIf { it in 0..100 }
            )
        }
        return WeatherForecast(
            locationName = location.name,
            timeZone = root.optString("timezone", "auto"),
            currentTemperatureCelsius = current.optDouble("temperature_2m", Double.NaN).takeIf(Double::isFinite),
            apparentTemperatureCelsius = current.optDouble("apparent_temperature", Double.NaN).takeIf(Double::isFinite),
            currentWeatherCode = current.optInt("weather_code", -1).takeIf { it >= 0 },
            windSpeedKmh = current.optDouble("wind_speed_10m", Double.NaN).takeIf(Double::isFinite),
            days = days,
            retrievedAtMillis = nowMillis()
        )
    }

    private fun parseJson(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (error: JSONException) {
        throw WeatherProviderFailure("WEATHER_INVALID_RESPONSE", error)
    }
}

internal class WeatherProviderFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

internal interface WeatherHttpTransport {
    @Throws(IOException::class)
    fun get(url: String): String
}

internal class UrlConnectionWeatherTransport : WeatherHttpTransport {
    override fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw WeatherProviderFailure("WEATHER_HTTP_$status")
            if (body.isBlank()) throw WeatherProviderFailure("WEATHER_EMPTY_RESPONSE")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

internal class AndroidPreferredWeatherCityStore(context: Context) : PreferredWeatherCityStore {
    private val preferences = context.applicationContext.getSharedPreferences("weather_location", Context.MODE_PRIVATE)

    override fun get(): String? = preferences.getString(KEY_CITY, null)?.trim()?.takeIf(String::isNotEmpty)

    override fun set(city: String?) {
        val normalized = city?.trim()?.take(MAX_CITY_CHARS)?.takeIf(String::isNotEmpty)
        preferences.edit().apply {
            if (normalized == null) remove(KEY_CITY) else putString(KEY_CITY, normalized)
        }.apply()
    }

    private companion object {
        const val KEY_CITY = "preferred_city"
        const val MAX_CITY_CHARS = 60
    }
}

internal class AndroidWeatherLocationSource(context: Context) : WeatherLocationSource {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun hasPermission(): Boolean = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun lastKnownLocation(): WeatherLocation? {
        if (!hasPermission()) return null
        val best = locationManager.getProviders(true).asSequence().mapNotNull { provider ->
            try { locationManager.getLastKnownLocation(provider) }
            catch (_: SecurityException) { null }
            catch (_: IllegalArgumentException) { null }
        }.filter { location ->
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            ageMs in 0..MAX_LOCATION_AGE_MS
        }.maxByOrNull(Location::getTime) ?: return null
        return WeatherLocation("Bulunduğun yerde", best.latitude, best.longitude)
    }

    private companion object { const val MAX_LOCATION_AGE_MS = 6 * 60 * 60 * 1_000L }
}

internal class AndroidWeatherForecastCache(context: Context) : WeatherForecastCache {
    private val preferences = context.applicationContext.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)

    override fun read(key: String): WeatherForecast? {
        val raw = preferences.getString(key, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val daysJson = root.optJSONArray("days") ?: return null
            val days = (0 until daysJson.length()).mapNotNull { index ->
                val day = daysJson.optJSONObject(index) ?: return@mapNotNull null
                WeatherDailyForecast(
                    date = day.optString("date"),
                    weatherCode = day.optInt("code", -1),
                    minimumCelsius = day.optDouble("min", Double.NaN).takeIf(Double::isFinite),
                    maximumCelsius = day.optDouble("max", Double.NaN).takeIf(Double::isFinite),
                    precipitationProbabilityPercent = day.optInt("rain", -1).takeIf { it in 0..100 }
                )
            }
            WeatherForecast(
                locationName = root.optString("location"),
                timeZone = root.optString("timezone"),
                currentTemperatureCelsius = root.optDouble("current", Double.NaN).takeIf(Double::isFinite),
                apparentTemperatureCelsius = root.optDouble("apparent", Double.NaN).takeIf(Double::isFinite),
                currentWeatherCode = root.optInt("currentCode", -1).takeIf { it >= 0 },
                windSpeedKmh = root.optDouble("wind", Double.NaN).takeIf(Double::isFinite),
                days = days,
                retrievedAtMillis = root.optLong("retrievedAt")
            )
        } catch (_: JSONException) {
            null
        }
    }

    override fun write(key: String, forecast: WeatherForecast) {
        val days = org.json.JSONArray()
        forecast.days.forEach { day ->
            days.put(JSONObject().put("date", day.date).put("code", day.weatherCode)
                .put("min", day.minimumCelsius).put("max", day.maximumCelsius)
                .put("rain", day.precipitationProbabilityPercent))
        }
        val raw = JSONObject().put("location", forecast.locationName).put("timezone", forecast.timeZone)
            .put("current", forecast.currentTemperatureCelsius).put("apparent", forecast.apparentTemperatureCelsius)
            .put("currentCode", forecast.currentWeatherCode).put("wind", forecast.windSpeedKmh)
            .put("retrievedAt", forecast.retrievedAtMillis).put("days", days)
        preferences.edit().putString(key, raw.toString()).apply()
    }
}
