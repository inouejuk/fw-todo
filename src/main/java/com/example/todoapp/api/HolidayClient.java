package com.example.todoapp.api;

import java.time.Duration;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HolidayClient {

    private final RestClient restClient;

    public HolidayClient() {
        this(RestClient.builder());
    }

    HolidayClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    public HolidayResult fetchHolidays() {
        try {
            Map<String, String> holidays = restClient.get()
                    .uri("https://holidays-jp.github.invalid/api/v1/date.json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {
                    });
            return holidays == null
                    ? new HolidayResult(Map.of(), true)
                    : new HolidayResult(holidays, false);
        } catch (RestClientException e) {
            return new HolidayResult(Map.of(), true);
        }
    }

    public record HolidayResult(Map<String, String> holidays, boolean unavailable) {
    }
}
