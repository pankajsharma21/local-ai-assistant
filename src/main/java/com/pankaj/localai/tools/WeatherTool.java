package com.pankaj.localai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Current weather and a short forecast via Open-Meteo — free, no API key, no account, no signup.
 *
 * Exists as a dedicated tool because general web search genuinely can't cover this. Marginalia
 * (our keyless web search) indexes independent, non-commercial sites and simply doesn't carry live
 * weather data — a real user question, "today weather in gurugram", came back with local news and
 * service listings and no weather at all. Weather needs a purpose-built data API, not a search
 * engine, so this calls one directly.
 *
 * Two steps, both keyless: geocode the place name to coordinates, then fetch the forecast for them.
 */
@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    /** WMO weather interpretation codes used by Open-Meteo's weather_code field. */
    private static final Map<Integer, String> WMO_CODES = Map.ofEntries(
            Map.entry(0, "clear sky"),
            Map.entry(1, "mainly clear"),
            Map.entry(2, "partly cloudy"),
            Map.entry(3, "overcast"),
            Map.entry(45, "fog"),
            Map.entry(48, "depositing rime fog"),
            Map.entry(51, "light drizzle"),
            Map.entry(53, "moderate drizzle"),
            Map.entry(55, "dense drizzle"),
            Map.entry(61, "slight rain"),
            Map.entry(63, "moderate rain"),
            Map.entry(65, "heavy rain"),
            Map.entry(66, "light freezing rain"),
            Map.entry(67, "heavy freezing rain"),
            Map.entry(71, "slight snowfall"),
            Map.entry(73, "moderate snowfall"),
            Map.entry(75, "heavy snowfall"),
            Map.entry(77, "snow grains"),
            Map.entry(80, "slight rain showers"),
            Map.entry(81, "moderate rain showers"),
            Map.entry(82, "violent rain showers"),
            Map.entry(85, "slight snow showers"),
            Map.entry(86, "heavy snow showers"),
            Map.entry(95, "thunderstorm"),
            Map.entry(96, "thunderstorm with slight hail"),
            Map.entry(99, "thunderstorm with heavy hail")
    );

    private final RestClient restClient = RestClient.builder()
            .defaultHeader("User-Agent", "LocalAiAssistant/1.0 (https://github.com/pankajsharma21/local-ai-assistant)")
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("""
        Get the current weather and today's/tomorrow's forecast for a city or place. Use this for ANY
        weather question - "weather in X", "is it raining in X", "temperature today", "will it rain
        tomorrow". Do NOT use searchWeb for weather; general web search does not return live weather
        data. Pass just the place name (e.g. "Gurugram", "London", "New York"), not the whole
        question. Temperatures are Celsius, wind is km/h.
        """)
    public String getWeather(String location) {
        log.info("getWeather called for: {}", location);
        try {
            JsonNode place = geocode(location);
            if (place == null) {
                return "Couldn't find a place called '" + location + "'. Ask the user to check the spelling "
                        + "or give a nearby larger city.";
            }
            String placeName = describePlace(place);
            JsonNode forecast = fetchForecast(place.path("latitude").asDouble(), place.path("longitude").asDouble());
            if (forecast == null) {
                return "Weather service returned no data for " + placeName + ".";
            }
            return format(placeName, forecast);
        } catch (Exception e) {
            log.warn("Weather lookup failed for '{}'", location, e);
            return "Weather lookup failed (" + e.getMessage() + "). Tell the user you couldn't reach the "
                    + "weather service rather than guessing.";
        }
    }

    private JsonNode geocode(String location) throws Exception {
        URI uri = URI.create("https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(location, StandardCharsets.UTF_8)
                + "&count=1&language=en&format=json");
        String raw = restClient.get().uri(uri).retrieve().body(String.class);
        if (raw == null) {
            return null;
        }
        JsonNode results = objectMapper.readTree(raw).path("results");
        return results.isArray() && !results.isEmpty() ? results.get(0) : null;
    }

    private JsonNode fetchForecast(double lat, double lon) throws Exception {
        URI uri = URI.create("https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,"
                + "weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=2");
        String raw = restClient.get().uri(uri).retrieve().body(String.class);
        return raw == null ? null : objectMapper.readTree(raw);
    }

    private String describePlace(JsonNode place) {
        StringBuilder sb = new StringBuilder(place.path("name").asText(""));
        String admin = place.path("admin1").asText("");
        String country = place.path("country").asText("");
        if (!admin.isBlank()) {
            sb.append(", ").append(admin);
        }
        if (!country.isBlank()) {
            sb.append(", ").append(country);
        }
        return sb.toString();
    }

    private String describeCode(JsonNode codeNode) {
        return WMO_CODES.getOrDefault(codeNode.asInt(-1), "unknown conditions");
    }

    private String format(String placeName, JsonNode forecast) {
        JsonNode current = forecast.path("current");
        JsonNode daily = forecast.path("daily");

        StringBuilder sb = new StringBuilder();
        sb.append("Weather for ").append(placeName)
          .append(" (local time ").append(current.path("time").asText("")).append(", source: Open-Meteo)\n\n");

        sb.append("Right now: ").append(describeCode(current.path("weather_code")))
          .append(", ").append(current.path("temperature_2m").asDouble()).append("°C")
          .append(" (feels like ").append(current.path("apparent_temperature").asDouble()).append("°C)")
          .append(", humidity ").append(current.path("relative_humidity_2m").asInt()).append('%')
          .append(", wind ").append(current.path("wind_speed_10m").asDouble()).append(" km/h\n");

        JsonNode days = daily.path("time");
        for (int i = 0; i < days.size(); i++) {
            sb.append(i == 0 ? "Today (" : "Tomorrow (").append(days.get(i).asText()).append("): ")
              .append(describeCode(daily.path("weather_code").get(i)))
              .append(", ").append(daily.path("temperature_2m_min").get(i).asDouble()).append("°C to ")
              .append(daily.path("temperature_2m_max").get(i).asDouble()).append("°C")
              .append(", ").append(daily.path("precipitation_probability_max").get(i).asInt())
              .append("% chance of precipitation\n");
        }
        return sb.toString().strip();
    }
}
