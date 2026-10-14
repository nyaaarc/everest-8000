package dev.everest8000.expedition.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudDeckTest {
    @Test
    void altitudeMappingRoundTrips() {
        assertEquals(2860f, WeatherField.altitudeMeters(-1285), 0.5f, "Lukla runway");
        assertEquals(8849f, WeatherField.altitudeMeters(2031), 1f, "summit");
        assertEquals(-100f, WeatherField.yForAltitude(5000f), 1f, "5,000 m");
        assertEquals(177f, WeatherField.yForAltitude(5500f), 1f, "5,500 m");
        for (float y : new float[]{-1285, -358, 63, 543, 1193, 2031}) {
            assertEquals(y, WeatherField.yForAltitude(WeatherField.altitudeMeters(y)), 0.01f);
        }
    }

    @Test
    void deckFollowsTheWeather() {
        float fair = CloudDeck.baseY(WeatherField.CALM);
        System.out.printf("fair-weather cloud base y %.1f (%,.0f m)%n", fair, WeatherField.altitudeMeters(fair));
        assertEquals(CloudDeck.FAIR_Y, fair, 0.01f);
        assertTrue(fair > 63 + 40 && fair < 192, "well above Base Camp, below vanilla's 192");
        assertTrue(WeatherField.altitudeMeters(fair) >= 5000 && WeatherField.altitudeMeters(fair) <= 5500.5f);

        WeatherField shower = new WeatherField(0f, 60f, 0f, 400f, 0.6f, -450f);
        assertEquals(-450f, CloudDeck.baseY(shower), 0.01f, "a valley shower pulls the deck down to its top");

        WeatherField storm = new WeatherField(0f, 60f, 0.8f, 300f, 0f, -600f);
        assertEquals(300f, CloudDeck.baseY(storm), 0.01f, "a storm puts it at the storm's base");

        WeatherField both = new WeatherField(0f, 60f, 0.8f, -100f, 0.6f, -450f);
        assertEquals(-100f, CloudDeck.baseY(both), 0.01f, "the storm wins over a shower");

        WeatherField building = new WeatherField(0f, 60f, 0.1f, 300f, 0f, -600f);
        float y = CloudDeck.baseY(building);
        assertTrue(y > fair && y < 300f, "partway there while a storm builds: " + y);
    }
}
