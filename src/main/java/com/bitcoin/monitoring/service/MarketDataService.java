package com.bitcoin.monitoring.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public final class MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String BLOCKCHAIN_CHARTS_BASE_URL = "https://api.blockchain.info";
    private static final String YAHOO_FINANCE_BASE_URL = "https://query1.finance.yahoo.com";
    private static final String COIN_ID_PATTERN = "[a-z0-9-]{1,64}";
    private static final String TICKER_PATTERN = "[A-Za-z0-9._-]{1,24}";

    private final ObjectMapper mapper;
    private final RestClient coinGecko;
    private final RestClient alternativeMe;
    private final RestClient blockchainCharts;
    private final RestClient yahooFinance;
    private final long cacheTtlMillis;
    private final Object historyRequestLock = new Object();
    private final Object coinGeckoHistoryRequestLock = new Object();
    private long nextHistoryRequestNanos;
    private long nextCoinGeckoHistoryRequestNanos;
    private long coinGeckoHistoryCooldownUntilNanos;
    private Map<String, Object> cachedSnapshot;
    private Map<String, Object> lastGoodSnapshot;
    private Instant cachedAt;
    private final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();

    public MarketDataService(ObjectMapper mapper,
            @Value("${market.coingecko.base-url}") String coinGeckoBaseUrl,
            @Value("${market.alternative-me.base-url}") String alternativeMeBaseUrl,
            @Value("${market.cache-ttl-ms}") long cacheTtlMillis) {
        this.mapper = mapper;
        this.coinGecko = createClient(coinGeckoBaseUrl);
        this.alternativeMe = createClient(alternativeMeBaseUrl);
        this.blockchainCharts = createClient(BLOCKCHAIN_CHARTS_BASE_URL);
        this.yahooFinance = createClient(YAHOO_FINANCE_BASE_URL);
        this.cacheTtlMillis = Math.max(1_000L, cacheTtlMillis);
    }

    public synchronized Map<String, Object> getOverviewSnapshot() {
        Instant now = Instant.now();
        if (cachedSnapshot != null && cachedAt.plusMillis(cacheTtlMillis).isAfter(now)) {
            return new LinkedHashMap<>(cachedSnapshot);
        }

        try {
            JsonNode market = fetchMarketData();
            List<String> unavailableProviders = new ArrayList<>();
            JsonNode global = fetchOptional(coinGecko, "/global", unavailableProviders, "CoinGecko global data");
            JsonNode history = fetchOptional(coinGecko,
                    "/coins/bitcoin/market_chart?vs_currency=usd&days=1&interval=hourly",
                    unavailableProviders, "CoinGecko price history");
            JsonNode fearGreed = fetchOptional(alternativeMe, "/fng/?limit=7&format=json",
                    unavailableProviders, "Alternative.me sentiment");
                JsonNode activeAddresses = fetchOptional(blockchainCharts,
                    "/charts/n-unique-addresses?timespan=30days&format=json&cors=true",
                    unavailableProviders, "Blockchain.com active addresses");

                Map<String, Object> snapshot = buildOverviewSnapshot(
                    market, global, history, activeAddresses, fearGreed, now, false);
            if (!unavailableProviders.isEmpty()) {
                snapshot.put("status", "DEGRADED");
                snapshot.put("error", "Some market data providers are temporarily unavailable");
            }
            cachedSnapshot = snapshot;
            cachedAt = now;
            lastGoodSnapshot = "CONNECTED".equals(snapshot.get("status")) ? snapshot : lastGoodSnapshot;
            return new LinkedHashMap<>(snapshot);
        } catch (RuntimeException exception) {
            log.warn("Market overview refresh failed: {}", exception.getMessage());
            Map<String, Object> fallback = lastGoodSnapshot == null
                    ? unavailableSnapshot() : new LinkedHashMap<>(lastGoodSnapshot);
            fallback.put("status", "DEGRADED");
            fallback.put("marketStatus", "DISCONNECTED");
            fallback.put("stale", lastGoodSnapshot != null);
            fallback.put("error", "Market data providers are temporarily unavailable");
            cachedSnapshot = fallback;
            cachedAt = now;
            return new LinkedHashMap<>(fallback);
        }
    }

    public Map<String, Object> getMarketHistory(String range) {
        return getMarketHistory("bitcoin", range, null, null);
    }

    public Map<String, Object> getMarketHistory(String range, Long from, Long to) {
        return getMarketHistory("bitcoin", range, from, to);
    }

    public Map<String, Object> getMarketHistory(String coinId, String range, Long from, Long to) {
        return getMarketHistory(coinId, range, from, to, null);
    }

    public Map<String, Object> getMarketHistory(
            String coinId, String range, Long from, Long to, String symbol) {
        String normalizedCoinId = coinId == null ? "bitcoin" : coinId.toLowerCase();
        if (!normalizedCoinId.matches(COIN_ID_PATTERN)) {
            throw new IllegalArgumentException("Unsupported coin identifier");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.toUpperCase();
        if (normalizedSymbol != null && !normalizedSymbol.matches(TICKER_PATTERN)) {
            throw new IllegalArgumentException("Unsupported coin symbol");
        }
        String normalizedRange = range == null ? "" : range;
        String days = switch (normalizedRange) {
            case "1", "7", "30", "365", "max" -> normalizedRange;
            case "custom" -> "custom";
            default -> throw new IllegalArgumentException("Unsupported market history range");
        };
        long nowEpochSeconds = Instant.now().getEpochSecond();
        if ("custom".equals(days) && (from == null || to == null || from >= to || to > nowEpochSeconds)) {
            throw new IllegalArgumentException("Custom history requires a valid past start and end time");
        }
        String cacheKey = normalizedCoinId + ":" + normalizedSymbol + ":" + days + ":" + from + ":" + to;
        Instant now = Instant.now();
        CachedHistory cached = historyCache.get(cacheKey);
        if (cached != null && cached.cachedAt().plusMillis(cacheTtlMillis).isAfter(now)) {
            return new LinkedHashMap<>(cached.snapshot());
        }

        boolean yahooAttempted = normalizedSymbol != null && !"bitcoin".equals(normalizedCoinId);
        if (yahooAttempted) {
            try {
                JsonNode response = requestHistoricalPrice(yahooFinance,
                        yahooHistoryPath(normalizedSymbol, days, from, to));
                Map<String, Object> yahooHistory = buildYahooHistorySnapshot(
                        response, normalizedRange, from, to, false);
                if (yahooHistory.get("history") instanceof List<?> history && !history.isEmpty()) {
                    historyCache.put(cacheKey, new CachedHistory(yahooHistory, now));
                    if (historyCache.size() > 32) {
                        historyCache.keySet().stream().findFirst().ifPresent(historyCache::remove);
                    }
                    return new LinkedHashMap<>(yahooHistory);
                }
            } catch (RuntimeException exception) {
                log.debug("Yahoo Finance historical price request failed for {}: {}", normalizedCoinId,
                        exception.getMessage());
            }
        }

        try {
            String path = "custom".equals(days)
                    ? "/coins/" + normalizedCoinId + "/market_chart/range?vs_currency=usd&from=" + from + "&to=" + to
                    : "/coins/" + normalizedCoinId + "/market_chart?vs_currency=usd&days=" + days;
            JsonNode response = requestHistoricalPrice(coinGecko, path);
            Map<String, Object> snapshot = buildHistorySnapshot(response, normalizedRange, from, to, false);
            if (snapshot.get("history") instanceof List<?> history && history.isEmpty()) {
                throw new IllegalStateException("CoinGecko returned no historical prices");
            }
            historyCache.put(cacheKey, new CachedHistory(snapshot, now));
            if (historyCache.size() > 32) {
                historyCache.keySet().stream().findFirst().ifPresent(historyCache::remove);
            }
            return new LinkedHashMap<>(snapshot);
        } catch (RuntimeException exception) {
            log.warn("CoinGecko historical price request failed: {}", exception.getMessage());
                if (cached != null && cached.snapshot().get("history") instanceof List<?> cachedHistory
                    && !cachedHistory.isEmpty()) {
                Map<String, Object> stale = new LinkedHashMap<>(cached.snapshot());
                stale.put("stale", true);
                return stale;
            }
            if (!yahooAttempted) {
                try {
                    String yahooSymbol = normalizedSymbol == null ? normalizedCoinId.toUpperCase() : normalizedSymbol;
                    String path = yahooHistoryPath(yahooSymbol, days, from, to);
                    JsonNode response = requestHistoricalPrice(yahooFinance, path);
                    Map<String, Object> fallback = buildYahooHistorySnapshot(response, normalizedRange, from, to, false);
                    if (fallback.get("history") instanceof List<?> history && !history.isEmpty()) {
                        historyCache.put(cacheKey, new CachedHistory(fallback, now));
                        if (historyCache.size() > 32) {
                            historyCache.keySet().stream().findFirst().ifPresent(historyCache::remove);
                        }
                        return new LinkedHashMap<>(fallback);
                    }
                } catch (RuntimeException fallbackException) {
                    log.warn("Yahoo Finance historical price request failed for {}: {}", normalizedCoinId,
                            fallbackException.getMessage());
                }
            }
            if ("bitcoin".equals(normalizedCoinId)) {
                try {
                    String timespan = switch (normalizedRange) {
                        case "1" -> "1days";
                        case "7" -> "7days";
                        case "30" -> "30days";
                        case "365" -> "1year";
                        default -> "all";
                    };
                    JsonNode response = request(blockchainCharts,
                            "/charts/market-price?timespan=" + timespan + "&format=json&cors=true");
                    Map<String, Object> fallback = buildBlockchainHistorySnapshot(response, normalizedRange,
                            from, to, false);
                    fallback.put("error", "CoinGecko is unavailable; showing Blockchain.com history");
                    historyCache.put(cacheKey, new CachedHistory(fallback, now));
                    return new LinkedHashMap<>(fallback);
                } catch (RuntimeException fallbackException) {
                    log.warn("Blockchain.com historical price request failed: {}", fallbackException.getMessage());
                }
            }
            Map<String, Object> unavailable = historySnapshot(List.of(), normalizedRange, "CoinGecko", false);
            unavailable.put("error", "CoinGecko and Yahoo Finance historical prices are temporarily unavailable");
            historyCache.put(cacheKey, new CachedHistory(unavailable, now));
            return new LinkedHashMap<>(unavailable);
        }
    }

    private static String yahooHistoryPath(String symbol, String range, Long from, Long to) {
        String interval;
        if ("custom".equals(range)) {
            long now = Instant.now().getEpochSecond();
            interval = from < now - 700L * 86_400 ? "1d" : "1h";
            return "/v8/finance/chart/" + symbol + "-USD?period1=" + from + "&period2=" + to
                    + "&interval=" + interval + "&events=history";
        }
        String yahooRange = switch (range) {
            case "1" -> "1d";
            case "7" -> "7d";
            case "30" -> "1mo";
            case "365" -> "1y";
            case "max" -> "max";
            default -> throw new IllegalArgumentException("Unsupported market history range");
        };
        interval = "1".equals(range) ? "5m" : "7".equals(range) || "30".equals(range) ? "1h" : "1d";
        return "/v8/finance/chart/" + symbol + "-USD?range=" + yahooRange + "&interval=" + interval;
    }

    public static Map<String, Object> buildYahooHistorySnapshot(
            JsonNode chartData, String range, Long from, Long to, boolean stale) {
        List<Map<String, Object>> history = new ArrayList<>();
        JsonNode result = chartData == null ? null : chartData.path("chart").path("result").path(0);
        JsonNode timestamps = result == null ? null : result.path("timestamp");
        JsonNode quotes = result == null ? null : result.path("indicators").path("quote").path(0).path("close");
        if (timestamps != null && timestamps.isArray() && quotes != null && quotes.isArray()) {
            for (int index = 0; index < Math.min(timestamps.size(), quotes.size()); index++) {
                JsonNode timestampNode = timestamps.get(index);
                JsonNode priceNode = quotes.get(index);
                if (!timestampNode.canConvertToLong() || !priceNode.isNumber()) continue;
                long timestamp = timestampNode.asLong();
                double price = priceNode.asDouble();
                if (price <= 0 || !Double.isFinite(price)
                        || (from != null && timestamp < from) || (to != null && timestamp > to)) continue;
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("timestamp", Instant.ofEpochSecond(timestamp).toString());
                point.put("priceUsd", price);
                history.add(point);
            }
        }
        return historySnapshot(history, range, "Yahoo Finance", stale);
    }

    public Map<String, Object> getPriceForecast(String range, int horizonDays) {
        return getMarketForecast("bitcoin", range, horizonDays, null, null);
    }

    public Map<String, Object> getMarketForecast(
            String coinId, String range, int horizonDays, Long from, Long to) {
        return getMarketForecast(coinId, range, horizonDays, from, to, null);
    }

    public Map<String, Object> getMarketForecast(
            String coinId, String range, int horizonDays, Long from, Long to, String symbol) {
        if (horizonDays < 1 || horizonDays > 90) {
            throw new IllegalArgumentException("Forecast horizon must be between 1 and 90 days");
        }
        Map<String, Object> historySnapshot = getMarketHistory(coinId, range, from, to, symbol);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) historySnapshot.get("history");
        Map<String, Object> forecast = buildForecastSnapshot(history, range, horizonDays,
            Boolean.TRUE.equals(historySnapshot.get("stale")));
        forecast.put("coinId", coinId == null ? "bitcoin" : coinId.toLowerCase());
        forecast.put("source", historySnapshot.getOrDefault("source", "CoinGecko"));
        if (historySnapshot.containsKey("error")) {
            forecast.put("historyError", historySnapshot.get("error"));
        }
        return forecast;
    }

    static Map<String, Object> buildForecastSnapshot(List<Map<String, Object>> history,
            String range, int horizonDays, boolean stale) {
        List<PricePoint> points = new ArrayList<>();
        for (Map<String, Object> item : history) {
            Object timestamp = item.get("timestamp");
            Object price = item.get("priceUsd");
            if (!(timestamp instanceof String time) || !(price instanceof Number value)
                    || !Double.isFinite(value.doubleValue()) || value.doubleValue() <= 0) continue;
            try {
                points.add(new PricePoint(Instant.parse(time), value.doubleValue()));
            } catch (DateTimeParseException exception) {
                log.debug("Ignoring invalid historical price timestamp");
            }
        }
        points.sort(java.util.Comparator.comparing(PricePoint::timestamp));

        Map<String, Object> forecast = new LinkedHashMap<>();
        forecast.put("source", "CoinGecko");
        forecast.put("model", "30-day log-linear trend baseline");
        forecast.put("range", range);
        forecast.put("horizonDays", horizonDays);
        forecast.put("historyPoints", points.size());
        forecast.put("stale", stale);
        if (points.size() < 32) {
            forecast.put("available", false);
            forecast.put("message", "At least 32 real historical observations are required for this range");
            forecast.put("forecastPriceUsd", null);
            forecast.put("forecastChangePct", null);
            forecast.put("signal", "HOLD");
            Map<String, Object> backtest = new LinkedHashMap<>();
            backtest.put("samples", 0);
            backtest.put("maeUsd", null);
            backtest.put("mapePct", null);
            backtest.put("directionalAccuracyPct", null);
            backtest.put("precisionPct", null);
            backtest.put("recallPct", null);
            backtest.put("f1Pct", null);
            backtest.put("truePositives", 0);
            backtest.put("falsePositives", 0);
            backtest.put("trueNegatives", 0);
            backtest.put("falseNegatives", 0);
            forecast.put("backtest", backtest);
            return forecast;
        }

        PricePoint latest = points.getLast();
        Instant forecastTime = latest.timestamp().plus(horizonDays, ChronoUnit.DAYS);
        Instant trainingStart = latest.timestamp().minus(30, ChronoUnit.DAYS);
        List<PricePoint> training = points.subList(lowerBound(points, 0, trainingStart), points.size());
        LogTrend currentTrend = fitLogTrend(training);

        double absoluteError = 0;
        double percentageError = 0;
        int directionMatches = 0;
        int truePositives = 0;
        int falsePositives = 0;
        int trueNegatives = 0;
        int falseNegatives = 0;
        int samples = 0;
        for (int originIndex = 31; originIndex < points.size(); originIndex++) {
            PricePoint origin = points.get(originIndex - 1);
            Instant targetTime = origin.timestamp().plus(horizonDays, ChronoUnit.DAYS);
            int targetIndex = lowerBound(points, originIndex, targetTime);
            if (targetIndex >= points.size()) break;
            PricePoint actual = points.get(targetIndex);
            if (ChronoUnit.DAYS.between(targetTime, actual.timestamp()) > 3) continue;
            List<PricePoint> backtestTraining = points.subList(
                    lowerBound(points, 0, origin.timestamp().minus(30, ChronoUnit.DAYS)), originIndex);
            if (backtestTraining.size() < 8) continue;
            double predicted = fitLogTrend(backtestTraining).predict(targetTime);
            absoluteError += Math.abs(predicted - actual.priceUsd());
            percentageError += Math.abs((predicted - actual.priceUsd()) / actual.priceUsd()) * 100;
            boolean predictedUp = predicted >= origin.priceUsd();
            boolean actualUp = actual.priceUsd() >= origin.priceUsd();
            if (predictedUp == actualUp) directionMatches++;
            if (predictedUp && actualUp) truePositives++;
            else if (predictedUp) falsePositives++;
            else if (actualUp) falseNegatives++;
            else trueNegatives++;
            samples++;
        }

        Map<String, Object> backtest = new LinkedHashMap<>();
        double precision = truePositives + falsePositives == 0
                ? Double.NaN : (double) truePositives / (truePositives + falsePositives);
        double recall = truePositives + falseNegatives == 0
                ? Double.NaN : (double) truePositives / (truePositives + falseNegatives);
        backtest.put("samples", samples);
        backtest.put("maeUsd", samples == 0 ? null : absoluteError / samples);
        backtest.put("mapePct", samples == 0 ? null : percentageError / samples);
        backtest.put("directionalAccuracyPct", samples == 0 ? null : 100.0 * directionMatches / samples);
        backtest.put("precisionPct", Double.isFinite(precision) ? 100.0 * precision : null);
        backtest.put("recallPct", Double.isFinite(recall) ? 100.0 * recall : null);
        backtest.put("f1Pct", Double.isFinite(precision) && Double.isFinite(recall)
                && precision + recall > 0 ? 200.0 * precision * recall / (precision + recall) : null);
        backtest.put("truePositives", truePositives);
        backtest.put("falsePositives", falsePositives);
        backtest.put("trueNegatives", trueNegatives);
        backtest.put("falseNegatives", falseNegatives);
        double forecastPrice = currentTrend.predict(forecastTime);
        double forecastChangePct = (forecastPrice / latest.priceUsd() - 1) * 100;
        boolean directionallySupported = samples >= 5 && 100.0 * directionMatches / samples >= 55.0;
        String signal = directionallySupported && forecastChangePct >= 2.0 ? "BUY WATCH"
            : directionallySupported && forecastChangePct <= -2.0 ? "SELL WATCH" : "HOLD";
        forecast.put("available", true);
        forecast.put("forecastFor", forecastTime.toString());
        forecast.put("forecastPriceUsd", forecastPrice);
        forecast.put("forecastChangePct", forecastChangePct);
        forecast.put("signal", signal);
        forecast.put("lastObservedAt", latest.timestamp().toString());
        forecast.put("lastObservedPriceUsd", latest.priceUsd());
        forecast.put("historyStart", points.getFirst().timestamp().toString());
        forecast.put("historyEnd", latest.timestamp().toString());
        forecast.put("backtest", backtest);
        return forecast;
    }

    private static int lowerBound(List<PricePoint> points, int start, Instant target) {
        int low = start;
        int high = points.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (points.get(middle).timestamp().isBefore(target)) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static LogTrend fitLogTrend(List<PricePoint> points) {
        Instant start = points.getFirst().timestamp();
        double meanX = 0;
        double meanY = 0;
        for (PricePoint point : points) {
            meanX += Duration.between(start, point.timestamp()).toMillis() / 86_400_000.0;
            meanY += Math.log(point.priceUsd());
        }
        meanX /= points.size();
        meanY /= points.size();
        double covariance = 0;
        double variance = 0;
        for (PricePoint point : points) {
            double x = Duration.between(start, point.timestamp()).toMillis() / 86_400_000.0 - meanX;
            covariance += x * (Math.log(point.priceUsd()) - meanY);
            variance += x * x;
        }
        double slope = variance == 0 ? 0 : covariance / variance;
        return new LogTrend(start, meanY - slope * meanX, slope);
    }

    private record PricePoint(Instant timestamp, double priceUsd) { }
    private record LogTrend(Instant start, double intercept, double slope) {
        double predict(Instant timestamp) {
            double days = Duration.between(start, timestamp).toMillis() / 86_400_000.0;
            return Math.exp(intercept + slope * days);
        }
    }

    private JsonNode fetchMarketData() {
        JsonNode rows = request(coinGecko,
            "/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=100&page=1&price_change_percentage=24h,7d");
        JsonNode bitcoinSource = null;
        for (JsonNode row : rows) {
            if ("bitcoin".equals(row.path("id").asText())) {
                bitcoinSource = row;
                break;
            }
        }
        if (bitcoinSource == null || !bitcoinSource.isObject()) {
            throw new IllegalStateException("CoinGecko returned no Bitcoin market data");
        }

        ObjectNode normalized = mapper.createObjectNode();
        ObjectNode bitcoin = normalized.putObject("bitcoin");
        copyMarketFields(bitcoinSource, bitcoin);
        String updatedAt = bitcoinSource.path("last_updated").asText(null);
        if (updatedAt != null) {
            try {
                bitcoin.put("last_updated_at", Instant.parse(updatedAt).getEpochSecond());
            } catch (DateTimeParseException exception) {
                log.debug("Ignoring invalid CoinGecko last_updated value");
            }
        }
        ArrayNode assets = normalized.putArray("assets");
        for (JsonNode row : rows) {
            ObjectNode asset = assets.addObject();
            asset.put("id", row.path("id").asText());
            asset.put("name", row.path("name").asText());
            asset.put("symbol", row.path("symbol").asText().toUpperCase());
            copyMarketFields(row, asset);
        }
        return normalized;
    }

    private static void copyMarketFields(JsonNode source, ObjectNode target) {
        copyField(source, target, "current_price", "usd");
        copyField(source, target, "market_cap", "usd_market_cap");
        copyField(source, target, "total_volume", "usd_24h_vol");
        copyField(source, target, "price_change_percentage_24h", "usd_24h_change");
        copyField(source, target, "price_change_percentage_7d_in_currency", "usd_7d_change");
        copyField(source, target, "market_cap_rank", "market_cap_rank");
        copyField(source, target, "circulating_supply", "circulating_supply");
        copyField(source, target, "total_supply", "total_supply");
        copyField(source, target, "max_supply", "max_supply");
        copyField(source, target, "ath", "ath");
        copyField(source, target, "ath_change_percentage", "ath_change_percentage");
        copyField(source, target, "ath_date", "ath_date");
    }

    private JsonNode fetchOptional(RestClient client, String path, List<String> unavailableProviders, String provider) {
        try {
            return request(client, path);
        } catch (RuntimeException exception) {
            log.warn("{} request failed: {}", provider, exception.getMessage());
            unavailableProviders.add(provider);
            return mapper.createObjectNode();
        }
    }

    private JsonNode request(RestClient client, String path) {
        JsonNode response = client.get().uri(path).retrieve().body(JsonNode.class);
        if (response == null || response.isNull()) {
            throw new IllegalStateException("Market provider returned an empty response");
        }
        return response;
    }

    private JsonNode requestHistoricalPrice(RestClient client, String path) {
        boolean coinGeckoRequest = client == coinGecko;
        Object requestLock = coinGeckoRequest ? coinGeckoHistoryRequestLock : historyRequestLock;
        synchronized (requestLock) {
            long now = System.nanoTime();
            if (coinGeckoRequest && now < coinGeckoHistoryCooldownUntilNanos) {
                throw new IllegalStateException("CoinGecko historical data is temporarily rate-limited");
            }
            long nextRequestNanos = coinGeckoRequest
                    ? nextCoinGeckoHistoryRequestNanos : nextHistoryRequestNanos;
            long delayNanos = nextRequestNanos - now;
            if (delayNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(delayNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while pacing historical price requests", exception);
                }
            }
            long next = System.nanoTime() + Duration.ofMillis(2_200).toNanos();
            if (coinGeckoRequest) nextCoinGeckoHistoryRequestNanos = next;
            else nextHistoryRequestNanos = next;
        }
        try {
            return request(client, path);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            if (coinGeckoRequest) {
                synchronized (coinGeckoHistoryRequestLock) {
                    coinGeckoHistoryCooldownUntilNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                }
            }
            throw exception;
        }
    }

    private Map<String, Object> unavailableSnapshot() {
        Map<String, Object> market = new LinkedHashMap<>();
        for (String field : List.of("priceUsd", "marketCapUsd", "volume24hUsd", "priceChange24hPct",
                "marketCapRank", "circulatingSupply", "totalSupply", "maxSupply", "allTimeHighUsd",
                "allTimeHighChangePct", "allTimeHighAt")) {
            market.put(field, null);
        }
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("totalMarketCapUsd", null);
        global.put("totalVolume24hUsd", null);
        global.put("btcDominancePct", null);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", "DISCONNECTED");
        snapshot.put("marketStatus", "DISCONNECTED");
        snapshot.put("market", market);
        snapshot.put("assets", List.of());
        snapshot.put("global", global);
        snapshot.put("history", List.of());
        snapshot.put("fearGreed", List.of());
        snapshot.put("activeAddresses", unavailableActiveAddresses());
        snapshot.put("marketUpdatedAt", null);
        snapshot.put("lastUpdated", Instant.now().toString());
        snapshot.put("stale", false);
        snapshot.put("error", "Real market data is unavailable until the provider reconnects");
        return snapshot;
    }

    private static RestClient createClient(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .defaultHeader("User-Agent", "bitcoin-traffic-monitor/1.0").build();
    }

    private static void copyField(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        JsonNode value = source.get(sourceField);
        if (value != null && !value.isNull()) {
            target.set(targetField, value.deepCopy());
        }
    }

    public static Map<String, Object> buildOverviewSnapshot(JsonNode marketData, JsonNode globalData,
            JsonNode historyData, JsonNode fearGreedData, Instant lastUpdated, boolean stale) {
        return buildOverviewSnapshot(marketData, globalData, historyData, null, fearGreedData, lastUpdated, stale);
        }

        public static Map<String, Object> buildOverviewSnapshot(JsonNode marketData, JsonNode globalData,
            JsonNode historyData, JsonNode activeAddressData, JsonNode fearGreedData, Instant lastUpdated, boolean stale) {
        JsonNode bitcoin = marketData == null ? null : marketData.path("bitcoin");
        JsonNode global = globalData == null ? null : globalData.path("data");

        Map<String, Object> market = new LinkedHashMap<>();
        market.put("priceUsd", number(bitcoin, "usd"));
        market.put("marketCapUsd", number(bitcoin, "usd_market_cap"));
        market.put("volume24hUsd", number(bitcoin, "usd_24h_vol"));
        market.put("priceChange24hPct", number(bitcoin, "usd_24h_change"));
        market.put("marketCapRank", number(bitcoin, "market_cap_rank"));
        market.put("circulatingSupply", number(bitcoin, "circulating_supply"));
        market.put("totalSupply", number(bitcoin, "total_supply"));
        market.put("maxSupply", number(bitcoin, "max_supply"));
        market.put("allTimeHighUsd", number(bitcoin, "ath"));
        market.put("allTimeHighChangePct", number(bitcoin, "ath_change_percentage"));
        market.put("allTimeHighAt", bitcoin == null ? null : bitcoin.path("ath_date").asText(null));

        Map<String, Object> globalSnapshot = new LinkedHashMap<>();
        globalSnapshot.put("totalMarketCapUsd", number(global == null ? null : global.path("total_market_cap"), "usd"));
        globalSnapshot.put("totalVolume24hUsd", number(global == null ? null : global.path("total_volume"), "usd"));
        globalSnapshot.put("btcDominancePct", number(global == null ? null : global.path("market_cap_percentage"), "btc"));
        Map<String, Object> activeAddresses = activeAddressSnapshot(activeAddressData);
        market.put("activeAddresses", activeAddresses);

        List<Map<String, Object>> history = new ArrayList<>();
        for (JsonNode point : historyData == null ? List.<JsonNode>of() : historyData.path("prices")) {
            if (point.size() < 2) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", Instant.ofEpochMilli(point.get(0).asLong()).toString());
            item.put("priceUsd", point.get(1).asDouble());
            history.add(item);
        }

        List<Map<String, Object>> fearGreed = new ArrayList<>();
        for (JsonNode reading : fearGreedData == null ? List.<JsonNode>of() : fearGreedData.path("data")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", number(reading, "value"));
            item.put("classification", reading.path("value_classification").asText(null));
            JsonNode timestamp = reading.get("timestamp");
            item.put("timestamp", timestamp == null || timestamp.isNull()
                    ? null : Instant.ofEpochSecond(timestamp.asLong()).toString());
            fearGreed.add(item);
        }

        String status = bitcoin != null && bitcoin.isObject() && global != null && global.isObject()
            ? "CONNECTED" : "DISCONNECTED";
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("marketStatus", status);
        snapshot.put("market", market);
        snapshot.put("assets", assetSnapshots(marketData == null ? null : marketData.path("assets")));
        snapshot.put("global", globalSnapshot);
        snapshot.put("history", history);
        snapshot.put("fearGreed", fearGreed);
        snapshot.put("activeAddresses", activeAddresses);
        snapshot.put("marketUpdatedAt", epochSeconds(bitcoin == null ? null : bitcoin.get("last_updated_at")));
        snapshot.put("lastUpdated", lastUpdated == null ? null : lastUpdated.toString());
        snapshot.put("stale", stale);
        return snapshot;
    }

    private static List<Map<String, Object>> assetSnapshots(JsonNode assets) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (assets == null || !assets.isArray()) return result;
        for (JsonNode asset : assets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", asset.path("id").asText());
            item.put("name", asset.path("name").asText());
            item.put("symbol", asset.path("symbol").asText());
            item.put("priceUsd", number(asset, "usd"));
            item.put("marketCapUsd", number(asset, "usd_market_cap"));
            item.put("volume24hUsd", number(asset, "usd_24h_vol"));
            item.put("priceChange24hPct", number(asset, "usd_24h_change"));
            item.put("priceChange7dPct", number(asset, "usd_7d_change"));
            item.put("marketCapRank", number(asset, "market_cap_rank"));
            item.put("circulatingSupply", number(asset, "circulating_supply"));
            item.put("totalSupply", number(asset, "total_supply"));
            item.put("maxSupply", number(asset, "max_supply"));
            item.put("allTimeHighUsd", number(asset, "ath"));
            item.put("allTimeHighAt", asset.path("ath_date").asText(null));
            result.add(item);
        }
        return result;
    }

    public static Map<String, Object> buildBlockchainHistorySnapshot(
            JsonNode chartData, String range, Long from, Long to, boolean stale) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (JsonNode point : chartData == null ? List.<JsonNode>of() : chartData.path("values")) {
            if (!point.path("x").canConvertToLong() || !point.path("y").isNumber()) continue;
            long timestamp = point.path("x").asLong();
            double price = point.path("y").asDouble();
            if (price <= 0 || !Double.isFinite(price)
                    || (from != null && timestamp < from) || (to != null && timestamp > to)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", Instant.ofEpochSecond(timestamp).toString());
            item.put("priceUsd", price);
            history.add(item);
        }
        return historySnapshot(history, range, "Blockchain.com", stale);
    }

    private static Map<String, Object> buildHistorySnapshot(
            JsonNode historyData, String range, Long from, Long to, boolean stale) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (JsonNode point : historyData == null ? List.<JsonNode>of() : historyData.path("prices")) {
            if (point.size() < 2) continue;
            long timestamp = point.get(0).asLong();
            if ((from != null && timestamp < from * 1000)
                    || (to != null && timestamp > to * 1000)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", Instant.ofEpochMilli(timestamp).toString());
            item.put("priceUsd", point.get(1).asDouble());
            history.add(item);
        }
        return historySnapshot(history, range, "CoinGecko", stale);
    }

    private static Map<String, Object> historySnapshot(
            List<Map<String, Object>> history, String range, String source, boolean stale) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("history", history);
        snapshot.put("range", range);
        snapshot.put("stale", stale);
        snapshot.put("source", source);
        snapshot.put("observationCount", history.size());
        snapshot.put("startDate", history.isEmpty() ? null : history.getFirst().get("timestamp"));
        snapshot.put("endDate", history.isEmpty() ? null : history.getLast().get("timestamp"));
        return snapshot;
    }

    private static Map<String, Object> activeAddressSnapshot(JsonNode chartData) {
        JsonNode values = chartData == null ? null : chartData.path("values");
        if (values == null || !values.isArray() || values.isEmpty()) return unavailableActiveAddresses();
        JsonNode latest = values.get(values.size() - 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", number(latest, "y"));
        result.put("timestamp", latest.path("x").canConvertToLong()
                ? Instant.ofEpochSecond(latest.path("x").asLong()).toString() : null);
        return result;
    }

    private static Map<String, Object> unavailableActiveAddresses() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", null);
        result.put("timestamp", null);
        return result;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            try {
                return Double.valueOf(value.asText());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private static String epochSeconds(JsonNode value) {
        return value == null || value.isNull() ? null : Instant.ofEpochSecond(value.asLong()).toString();
    }

    private record CachedHistory(Map<String, Object> snapshot, Instant cachedAt) { }
}