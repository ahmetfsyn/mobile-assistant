package com.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WeatherServiceTest {
    @Test
    fun parserRecognizesCurrentTodayTomorrowRainWeekendAndExplicitCity() {
        assertEquals(WeatherQueryKind.CURRENT, WeatherQueryParser.parse("Ayşe, hava nasıl?")?.kind)
    }

    @Test
    fun parserKeepsCorrectStructuredQueries() {
        val current = WeatherQueryParser.parse("Ayşe, Mersin'de hava nasıl?")
        assertEquals(WeatherQueryKind.CURRENT, current?.kind)
        assertEquals("Mersin", current?.explicitCity)
        assertEquals(WeatherQueryKind.TODAY_RANGE, WeatherQueryParser.parse("Bugün hava kaç derece?")?.kind)
        assertEquals(WeatherQueryKind.TOMORROW, WeatherQueryParser.parse("Yarın hava nasıl?")?.kind)
        assertEquals(WeatherQueryKind.TOMORROW_RAIN, WeatherQueryParser.parse("Yarın yağmur yağacak mı?")?.kind)
        assertEquals(WeatherQueryKind.TODAY_RAIN, WeatherQueryParser.parse("Bugün yağmur yağacak mı?")?.kind)
        assertEquals(WeatherQueryKind.WEEKEND, WeatherQueryParser.parse("Hafta sonu hava nasıl?")?.kind)
        assertNull(WeatherQueryParser.parse("Saat kaç?"))
    }

    @Test
    fun explicitAndConfiguredCitiesDoNotRequireLocationPermission() {
        val api = FakeWeatherApi()
        val cities = FakeCityStore("İzmir")
        val processor = processor(api = api, cities = cities)

        val explicit = processor.process("Mersin'de hava nasıl?")
        assertTrue(explicit is WeatherCommandResult.Handled)
        assertEquals("Mersin", api.lastLocatedCity)
        assertEquals(null, api.lastForecastLocation?.name?.takeIf { it == "Bulunduğun yerde" })

        val configured = processor.process("Hava nasıl?")
        assertTrue(configured is WeatherCommandResult.Handled)
        assertEquals("İzmir", api.lastLocatedCity)
    }

    @Test
    fun unconfiguredLocationRequestsPermissionOnlyWhenWeatherIsRequested() {
        val source = FakeLocationSource(permission = false)
        val processor = processor(locationSource = source)
        assertTrue(processor.process("Hava nasıl?") is WeatherCommandResult.NeedsLocationPermission)
        assertEquals(WeatherCommandResult.NotHandled, processor.process("Bugün tarih ne?"))

        val denied = processor.process("Hava nasıl?", WeatherPermissionDecision.DENIED) as WeatherCommandResult.Handled
        assertTrue(denied.message.contains("şehir adı söyleyebilir"))
    }

    @Test
    fun deviceLocationUsesOnlyLastKnownCoordinatesWhenPermissionIsGranted() {
        val source = FakeLocationSource(permission = true, location = WeatherLocation("Bulunduğun yerde", 36.8, 34.6))
        val api = FakeWeatherApi()
        val processor = processor(api = api, locationSource = source, cities = FakeCityStore(null))

        assertTrue(processor.process("Hava nasıl?") is WeatherCommandResult.Handled)
        assertEquals("Bulunduğun yerde", api.lastForecastLocation?.name)
        assertEquals(1, source.lastKnownReadCount)
    }

    @Test
    fun unavailableLocationAsksForCityWithoutInventingWeather() {
        val processor = processor(locationSource = FakeLocationSource(permission = true, location = null), cities = FakeCityStore(null))
        val result = processor.process("Bugün hava nasıl?") as WeatherCommandResult.Handled
        assertTrue(result.message.contains("Hangi şehir"))
        assertFalse(result.message.contains("derece"))
    }

    @Test
    fun providerFailureUsesDatedCacheOrClearTurkishOfflineMessage() {
        val api = FakeWeatherApi().apply { failForecast = true }
        val cache = FakeCache()
        val forecast = sampleForecast(retrievedAt = 1_800_000_000_000L)
        cache.write("city:mersin", forecast)
        val processor = processor(api = api, cache = cache, cities = FakeCityStore(null), now = { 1_800_000_600_000L })

        val cached = processor.process("Mersin'de yarın yağmur yağacak mı?") as WeatherCommandResult.Handled
        assertTrue(cached.message.contains("Canlı veriye ulaşamadım"))
        assertTrue(cached.message.contains("Saat "))
        assertTrue(cached.message.contains("yüzde 60"))

        val noCache = processor(api = api, cities = FakeCityStore("Ankara"))
            .process("Hava nasıl?") as WeatherCommandResult.Handled
        assertTrue(noCache.message.contains("güncel hava verisine ulaşamıyorum"))
    }

    @Test
    fun formatterUsesRealForecastValuesAndNaturalTurkish() {
        val forecast = sampleForecast()
        assertEquals(
            "Mersin'de hava 22 derece, hissedilen 21 derece ve parçalı bulutlu.",
            WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.CURRENT), forecast)
        )
        assertEquals(
            "Bugün Mersin'de en yüksek 24, en düşük 17 derece.",
            WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.TODAY_RANGE), forecast)
        )
        assertEquals(
            "Yarın Mersin'de yağmurlu, sıcaklık 18 ile 23 derece arası.",
            WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.TOMORROW), forecast)
        )
        assertEquals(
            "Yarın Mersin'de yağmur ihtimali yüzde 60.",
            WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.TOMORROW_RAIN), forecast)
        )
        assertNotNull(WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.WEEKEND), forecast))
    }

    @Test
    fun openMeteoClientBuildsKeylessRequestsAndParsesForecast() {
        val transport = RecordingWeatherTransport(
            """{"results":[{"name":"Mersin","country_code":"TR","latitude":36.8,"longitude":34.6}]}""",
            """{"timezone":"Europe/Istanbul","current":{"temperature_2m":22,"apparent_temperature":21,"weather_code":2,"wind_speed_10m":11},"daily":{"time":["2026-11-13","2026-11-14"],"weather_code":[2,61],"temperature_2m_max":[24,23],"temperature_2m_min":[17,18],"precipitation_probability_max":[10,60]}}"""
        )
        val api = OpenMeteoWeatherApi(transport, nowMillis = { 123L })
        val location = api.locate("Mersin")
        val forecast = api.forecast(location)

        assertEquals("Mersin", location.name)
        assertEquals(36.8, location.latitude, 0.0)
        assertTrue(transport.urls[0].contains("geocoding-api.open-meteo.com"))
        assertTrue(transport.urls[1].contains("precipitation_probability_max"))
        assertFalse(transport.urls.any { it.contains("apikey") })
        assertEquals(60, forecast.days[1].precipitationProbabilityPercent)
        assertEquals(123L, forecast.retrievedAtMillis)
    }

    @Test
    fun todayRainAndCurrentFeelsLikeUseRetrievedValues() {
        val forecast = sampleForecast()
        assertEquals(
            "Bugün Mersin'de yağmur ihtimali yüzde 10.",
            WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.TODAY_RAIN), forecast)
        )
        assertTrue(WeatherReplyFormatter.format(WeatherQuery(WeatherQueryKind.CURRENT), forecast).contains("hissedilen 21 derece"))
    }

    @Test
    fun staleCacheIsNotPresentedAsCurrentWeather() {
        val api = FakeWeatherApi().apply { failForecast = true }
        val cache = FakeCache()
        cache.write("city:mersin", sampleForecast(retrievedAt = 1_800_000_000_000L))
        val processor = processor(api, cache, FakeCityStore("Mersin"), now = { 1_800_000_000_000L + 4 * 60 * 60 * 1_000L })

        val result = processor.process("Hava nasıl?") as WeatherCommandResult.Handled
        assertTrue(result.message.contains("eski olduğu için kullanmadım"))
        assertFalse(result.message.contains("22 derece"))
    }

    @Test
    fun invalidProviderJsonIsReportedAsAnIoFailure() {
        val api = OpenMeteoWeatherApi(RecordingWeatherTransport("not-json"))
        try {
            api.locate("Mersin")
            throw AssertionError("invalid provider response should fail")
        } catch (expected: WeatherProviderFailure) {
            assertTrue(expected.message.orEmpty().contains("WEATHER_INVALID_RESPONSE"))
        }
    }

    private fun processor(
        api: FakeWeatherApi = FakeWeatherApi(),
        cache: FakeCache = FakeCache(),
        cities: FakeCityStore = FakeCityStore(null),
        locationSource: FakeLocationSource = FakeLocationSource(permission = true, location = WeatherLocation("Mersin", 36.8, 34.6)),
        now: () -> Long = { 1_800_000_000_000L }
    ) = WeatherCommandProcessor(api, cache, cities, locationSource, now, WeatherDiagnosticLogger { })

    private fun sampleForecast(retrievedAt: Long = 1_800_000_000_000L) = WeatherForecast(
        locationName = "Mersin",
        timeZone = "Europe/Istanbul",
        currentTemperatureCelsius = 22.0,
        apparentTemperatureCelsius = 21.0,
        currentWeatherCode = 2,
        windSpeedKmh = 11.0,
        days = listOf(
            WeatherDailyForecast("2026-11-13", 2, 17.0, 24.0, 10),
            WeatherDailyForecast("2026-11-14", 61, 18.0, 23.0, 60),
            WeatherDailyForecast("2026-11-15", 3, 16.0, 21.0, 20)
        ),
        retrievedAtMillis = retrievedAt
    )

    private class FakeWeatherApi : WeatherForecastApi {
        var lastLocatedCity: String? = null
        var lastForecastLocation: WeatherLocation? = null
        var failForecast = false
        override fun locate(city: String): WeatherLocation {
            lastLocatedCity = city
            return WeatherLocation(city, 36.8, 34.6)
        }
        override fun forecast(location: WeatherLocation): WeatherForecast {
            if (failForecast) throw IOException("offline")
            lastForecastLocation = location
            return WeatherServiceTest().sampleForecast()
        }
    }

    private class FakeCache : WeatherForecastCache {
        private val values = mutableMapOf<String, WeatherForecast>()
        override fun read(key: String): WeatherForecast? = values[key]
        override fun write(key: String, forecast: WeatherForecast) { values[key] = forecast }
    }

    private class FakeCityStore(private val city: String?) : PreferredWeatherCityStore {
        override fun get(): String? = city
        override fun set(city: String?) = Unit
    }

    private class FakeLocationSource(
        private val permission: Boolean,
        private val location: WeatherLocation? = null
    ) : WeatherLocationSource {
        var lastKnownReadCount = 0
        override fun hasPermission(): Boolean = permission
        override fun lastKnownLocation(): WeatherLocation? {
            lastKnownReadCount++
            return location
        }
    }

    private class RecordingWeatherTransport(vararg val responses: String) : WeatherHttpTransport {
        val urls = mutableListOf<String>()
        private var index = 0
        override fun get(url: String): String {
            urls += url
            return responses[index++]
        }
    }
}
