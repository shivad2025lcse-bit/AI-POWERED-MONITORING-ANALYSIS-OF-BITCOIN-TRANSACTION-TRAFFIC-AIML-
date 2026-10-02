package com.bitcoin.monitoring.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class MarketDataServiceTest {
    @Test
    void customHistoryRejectsMissingReversedAndFutureRanges() {
        MarketDataService service = new MarketDataService(new ObjectMapper(),
                "https://api.coingecko.com/api/v3", "https://api.alternative.me", 60_000);
        long now = Instant.now().getEpochSecond();

        assertThrows(IllegalArgumentException.class, () -> service.getMarketHistory("custom", null, now));
        assertThrows(IllegalArgumentException.class, () -> service.getMarketHistory("custom", now, now));
        assertThrows(IllegalArgumentException.class, () -> service.getMarketHistory("custom", now - 60, now + 60));
    }

    @Test
    void blockchainHistoryUsesPositivePricesAndSelectedDateBounds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode chart = mapper.readTree(
                "{\"values\":[{\"x\":1231286400,\"y\":0},{\"x\":1282089600,\"y\":0.07},{\"x\":1282435200,\"y\":0.08}]}");

        Map<String, Object> result = MarketDataService.buildBlockchainHistorySnapshot(
                chart, "custom", 1282000000L, 1282200000L, false);

        assertEquals("Blockchain.com", result.get("source"));
        assertEquals(1, result.get("observationCount"));
        assertEquals(0.07, ((Map<String, Object>) ((List<?>) result.get("history")).getFirst()).get("priceUsd"));
    }

    @Test
    void forecastAndWalkForwardMetricsUseOnlyHistoricalObservations() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        List<Map<String, Object>> history = new ArrayList<>();
        for (int day = 0; day < 180; day++) {
            history.add(Map.of(
                    "timestamp", start.plus(day, ChronoUnit.DAYS).toString(),
                    "priceUsd", 100 * Math.exp(day * 0.01)));
        }

        Map<String, Object> result = MarketDataService.buildForecastSnapshot(history, "max", 7, false);
        Map<String, Object> backtest = (Map<String, Object>) result.get("backtest");

        assertEquals(true, result.get("available"));
        assertEquals(100 * Math.exp(186 * 0.01), (double) result.get("forecastPriceUsd"), 1e-8);
        assertTrue((int) backtest.get("samples") > 0);
        assertEquals(0.0, (double) backtest.get("mapePct"), 1e-8);
        assertEquals(100.0, (double) backtest.get("directionalAccuracyPct"));
        assertEquals("BUY WATCH", result.get("signal"));
        assertEquals(100 * (Math.exp(0.07) - 1), (double) result.get("forecastChangePct"), 1e-8);
    }

    @Test
    void forecastExplainsWhenSelectedHistoryIsTooShort() {
        Map<String, Object> result = MarketDataService.buildForecastSnapshot(List.of(), "7", 7, false);

        assertEquals(false, result.get("available"));
        assertNotNull(result.get("message"));
    }

    @Test
    void buildSnapshotUsesLatestMarketMetricsAndFearGreedReadings() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode market = mapper.readTree(
                "{\"bitcoin\":{\"usd\":62000,\"usd_market_cap\":1230000000000,\"usd_24h_vol\":45000000000,\"usd_24h_change\":2.5,\"last_updated_at\":1710000000}}");
        JsonNode global = mapper.readTree(
                "{\"data\":{\"total_market_cap\":{\"usd\":2000000000000},\"total_volume\":{\"usd\":90000000000},\"market_cap_percentage\":{\"btc\":52.5}}}");
        JsonNode history = mapper.readTree("{\"prices\":[[1710000000000,61000],[1710003600000,61500]]}");
        JsonNode activeAddresses = mapper.readTree("{\"values\":[{\"x\":1710000000,\"y\":510000}]}");
        JsonNode fearGreed = mapper
                .readTree("{\"data\":[{\"value\":75,\"value_classification\":\"Greed\",\"timestamp\":1710000000}]}");

        Map<String, Object> snapshot = MarketDataService.buildOverviewSnapshot(
                market, global, history, activeAddresses, fearGreed, null, false);

        assertEquals("CONNECTED", snapshot.get("status"));
        assertEquals(62000.0, ((Map<String, Object>) snapshot.get("market")).get("priceUsd"));
        assertEquals(52.5, ((Map<String, Object>) snapshot.get("global")).get("btcDominancePct"));
        assertNotNull(snapshot.get("history"));
        assertEquals(1, ((List<?>) snapshot.get("fearGreed")).size());
        assertEquals(510000.0, ((Map<String, Object>) snapshot.get("activeAddresses")).get("count"));
    }
}
