package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void parsesCoordinates() {
        assertArrayEquals(new double[] {1.29, 103.85}, Weather.parseLatLon("1.29,103.85"), 1e-9);
        assertArrayEquals(new double[] {-33.9, 151.2}, Weather.parseLatLon(" -33.9 , 151.2 "), 1e-9);
        assertNull(Weather.parseLatLon("Singapore"));
        assertNull(Weather.parseLatLon("91,0"));
        assertNull(Weather.parseLatLon("0,181"));
        assertNull(Weather.parseLatLon("1,2,3"));
        assertNull(Weather.parseLatLon(null));
    }

    @Test
    public void parsesGeocode() throws Exception {
        String body = "{\"results\":[{\"name\":\"Singapore\",\"latitude\":1.28967,\"longitude\":103.85007,\"country\":\"Singapore\"}]}";
        assertArrayEquals(new double[] {1.28967, 103.85007}, Weather.parseGeocode(body), 1e-9);
        assertNull(Weather.parseGeocode("{\"generationtime_ms\":0.5}"));
        assertNull(Weather.parseGeocode("{\"results\":[]}"));
    }

    @Test
    public void parsesForecast() throws Exception {
        String body = "{\"latitude\":1.3,\"current\":{\"time\":\"2026-10-20T00:15\",\"temperature_2m\":27.6,\"weather_code\":3}}";
        Weather.Reading r = Weather.parseForecast(body, 42L);
        assertEquals(27.6, r.temp, 1e-9);
        assertEquals(3, r.code);
        assertEquals(42L, r.at);
        for (String bad : new String[] {"{}", "{\"current\":{\"weather_code\":1}}", "{\"current\":{\"temperature_2m\":500,\"weather_code\":1}}", "nope"}) {
            try {
                Weather.parseForecast(bad, 0);
                fail(bad);
            } catch (Exception expected) {
                // refused
            }
        }
    }

    @Test
    public void picksIcons() {
        assertEquals("☀", Weather.icon(0));
        assertEquals("⛅", Weather.icon(2));
        assertEquals("☁", Weather.icon(3));
        assertEquals("≡", Weather.icon(45));
        assertEquals("☂", Weather.icon(61));
        assertEquals("☂", Weather.icon(81));
        assertEquals("❄", Weather.icon(73));
        assertEquals("⚡", Weather.icon(95));
        assertEquals("", Weather.icon(42));
    }

    @Test
    public void formatsAndRoundTrips() {
        assertEquals("31°", Weather.formatTemp(30.6));
        assertEquals("-2°", Weather.formatTemp(-2.4));
        Weather.Reading r = Weather.decodeLast(Weather.encodeLast(new Weather.Reading(27.6, 3, 40, 30, 1234L)));
        assertEquals(27.6, r.temp, 1e-9);
        assertEquals(3, r.code);
        assertEquals(40, r.rain1h);
        assertEquals(30, r.rain2h);
        assertEquals(-1, Weather.decodeLast("27.6,3,1234").rain1h);   // saved by an older version
        assertEquals(1234L, r.at);
        assertNull(Weather.decodeLast("garbage"));
        assertNull(Weather.decodeLast(null));
    }

    @Test
    public void rainChanceForTheNextTwoHours() throws Exception {
        String body = "{\"current\":{\"temperature_2m\":28.5,\"weather_code\":3},"
                + "\"hourly\":{\"precipitation_probability\":[8,40,30,90]}}";
        Weather.Reading r = Weather.parseForecast(body, 0);
        assertEquals(40, r.rain1h);
        assertEquals(30, r.rain2h);
        assertEquals(-1, Weather.parseForecast("{\"current\":{\"temperature_2m\":28.5,\"weather_code\":3}}", 0).rain1h);
        assertEquals("Rain: 40% 1h 30% 2h", Weather.formatRain(40, 30));
        assertEquals("Rain: 5% 1h 25% 2h", Weather.formatRain(5, 25));
        assertEquals("", Weather.formatRain(10, 5));
        assertEquals("", Weather.formatRain(-1, -1));
    }

    @Test
    public void airQuality() throws Exception {
        assertEquals(120, Weather.parseAqi("{\"current\":{\"time\":\"x\",\"us_aqi\":120}}"));
        assertEquals(-1, Weather.parseAqi("{\"current\":{\"us_aqi\":null}}"));
        assertEquals(0, Weather.aqiColor(50));
        assertEquals(0xFFFFD60A, Weather.aqiColor(51));
        assertEquals(0xFFFF9F0A, Weather.aqiColor(101));
        assertEquals(0xFFFF453A, Weather.aqiColor(151));
        Weather.Reading r = Weather.decodeLast(Weather.encodeLast(new Weather.Reading(27.6, 3, 40, 30, 120, 1L)));
        assertEquals(120, r.aqi);
    }
}
