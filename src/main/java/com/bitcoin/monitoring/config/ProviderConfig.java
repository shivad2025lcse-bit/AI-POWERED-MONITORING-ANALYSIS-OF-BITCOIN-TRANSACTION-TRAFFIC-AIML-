package com.bitcoin.monitoring.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProviderConfig {
    @Bean
    RestClient blockchainRestClient(@Value("${blockchain.api.base-url}") String baseUrl) {
        return createBlockchainClient(baseUrl);
    }

    @Bean
    RestClient blockchainFallbackRestClient(
            @Value("${blockchain.fallback.api-base-url:https://blockstream.info/api}") String baseUrl) {
        return createBlockchainClient(baseUrl);
    }

    private RestClient createBlockchainClient(String baseUrl) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "bitcoin-traffic-monitor/1.0").build();
    }
}