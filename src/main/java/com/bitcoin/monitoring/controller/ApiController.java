package com.bitcoin.monitoring.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bitcoin.monitoring.entity.AlertSeverity;
import com.bitcoin.monitoring.entity.AnomalyLevel;
import com.bitcoin.monitoring.entity.BitcoinBlock;
import com.bitcoin.monitoring.entity.BitcoinTransaction;
import com.bitcoin.monitoring.entity.NetworkAlert;
import com.bitcoin.monitoring.provider.BlockchainDataProvider;
import com.bitcoin.monitoring.repository.BitcoinBlockRepository;
import com.bitcoin.monitoring.repository.BitcoinTransactionRepository;
import com.bitcoin.monitoring.repository.NetworkAlertRepository;
import com.bitcoin.monitoring.service.MarketDataService;
import com.bitcoin.monitoring.service.MempoolSnapshotService;
import com.bitcoin.monitoring.service.MonitoringState;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final BitcoinBlockRepository blocks;
    private final BitcoinTransactionRepository transactions;
    private final NetworkAlertRepository alerts;
    private final MonitoringState monitoring;
    private final MempoolSnapshotService mempoolSnapshot;
    private final MarketDataService marketData;
    private final BlockchainDataProvider provider;

    public ApiController(BitcoinBlockRepository blocks, BitcoinTransactionRepository transactions,
            NetworkAlertRepository alerts, MonitoringState monitoring,
            MempoolSnapshotService mempoolSnapshot, MarketDataService marketData,
            BlockchainDataProvider provider) {
        this.blocks = blocks;
        this.transactions = transactions;
        this.alerts = alerts;
        this.monitoring = monitoring;
        this.mempoolSnapshot = mempoolSnapshot;
        this.marketData = marketData;
        this.provider = provider;
    }

    @GetMapping("/blocks/latest")
    public BitcoinBlock latestBlock() {
        return blocks.findFirstByOrderByHeightDesc().orElseThrow(() -> notFound("No block has been synchronized yet"));
    }

    @GetMapping("/blocks")
    public List<BitcoinBlock> listBlocks(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return blocks.findAllByOrderByHeightDesc(PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    @GetMapping("/blocks/{height}")
    public BitcoinBlock blockByHeight(@PathVariable long height) {
        if (height < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Block height must not be negative");
        return blocks.findFirstByHeightOrderByTimestampDesc(height)
                .orElseThrow(() -> notFound("Block not found locally"));
    }

    @GetMapping("/blocks/hash/{hash}")
    public BitcoinBlock blockByHash(@PathVariable String hash) {
        return blocks.findById(hash).orElseThrow(() -> notFound("Block not found locally"));
    }

    @GetMapping("/transactions")
    public Page<BitcoinTransaction> listTransactions(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String q,
            @RequestParam(required = false) AnomalyLevel level) {
        PageRequest request = PageRequest.of(Math.max(0, page), boundedSize(size));
        if (level != null)
            return transactions.findByAnomalyLevelOrderByTimestampDesc(level, request);
        if (q != null && !q.isBlank())
            return transactions.findByTxidContainingIgnoreCaseOrderByTimestampDesc(q.trim(), request);
        return transactions.findAllByOrderByTimestampDesc(request);
    }

    @GetMapping("/transactions/{txid}")
    public Object transaction(@PathVariable String txid) {
        var local = transactions.findById(txid);
        if (local.isPresent())
            return local.get();
        try {
            JsonNode external = provider.getTransaction(txid);
            return Map.of("source", "external", "transaction", external);
        } catch (RuntimeException exception) {
            throw notFound("Transaction was not found locally or by the configured provider");
        }
    }

    @GetMapping("/statistics/current")
    public Map<String, Object> currentStatistics() {
        Instant now = Instant.now();
        Instant minuteAgo = now.minus(1, ChronoUnit.MINUTES);
        Instant fiveMinutesAgo = now.minus(5, ChronoUnit.MINUTES);
        Instant hourAgo = now.minus(1, ChronoUnit.HOURS);
        List<BitcoinBlock> recentBlocks = blocks.findAllByOrderByHeightDesc(PageRequest.of(0, 24));
        List<BitcoinTransaction> lastHour = transactions.findByTimestampAfterOrderByTimestampAsc(hourAgo);
        double averageFeeRate = lastHour.stream().mapToDouble(tx -> tx.getFeeRate().doubleValue()).average().orElse(0);
        double averagePerBlock = recentBlocks.stream().mapToInt(BitcoinBlock::getTransactionCount).average().orElse(0);
        BigDecimal volume = lastHour.stream().map(BitcoinTransaction::getOutputValue).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latestBlock", blocks.findFirstByOrderByHeightDesc().orElse(null));
        result.put("transactionsPerMinute", transactions.countByTimestampAfter(minuteAgo));
        result.put("transactionsPerFiveMinutes", transactions.countByTimestampAfter(fiveMinutesAgo));
        result.put("transactionsPerHour", lastHour.size());
        result.put("blocksPerHour", blocks.findByTimestampAfterOrderByHeightAsc(hourAgo).size());
        result.put("averageTransactionsPerBlock", averagePerBlock);
        result.put("averageFeeRateSatVbyte", averageFeeRate);
        result.put("averageTransactionSize",
                lastHour.stream().mapToLong(BitcoinTransaction::getTransactionSize).average().orElse(0));
        result.put("volumeBtcLastHour", volume);
        result.put("monitoredTransactions", transactions.count());
        result.put("anomalyCount", transactions.countByAnomalyLevelNot(AnomalyLevel.NORMAL));
        Map<String, Long> riskCounts = new LinkedHashMap<>();
        for (AnomalyLevel level : AnomalyLevel.values()) {
            riskCounts.put(level.name(), transactions.countByAnomalyLevel(level));
        }
        result.put("riskCounts", riskCounts);
        result.put("openAlerts", alerts.countByResolvedFalse());
        result.put("providerStatus", monitoring.getProviderStatus());
        result.put("lastSuccessfulSync", monitoring.getLastSuccessfulSync());
        return result;
    }

    @GetMapping("/statistics/transactions-per-minute")
    public Map<String, Object> transactionsPerMinute() {
        Instant since = Instant.now().minus(60, ChronoUnit.MINUTES);
        List<BitcoinTransaction> points = transactions.findByTimestampAfterOrderByTimestampAsc(since);
        Map<String, Long> perMinute = points.stream().collect(java.util.stream.Collectors.groupingBy(
                tx -> tx.getTimestamp().truncatedTo(ChronoUnit.MINUTES).toString(), java.util.TreeMap::new,
                java.util.stream.Collectors.counting()));
        return Map.of("labels", perMinute.keySet(), "values", perMinute.values());
    }

    @GetMapping("/statistics/fees")
    public Map<String, Object> fees() {
        List<BitcoinTransaction> points = transactions.findTop100ByOrderByTimestampDesc();
        return Map.of("labels", points.stream().map(tx -> tx.getTimestamp().toString()).toList(),
                "values", points.stream().map(tx -> tx.getFeeRate()).toList());
    }

    @GetMapping("/statistics/traffic")
    public Map<String, Object> traffic(@RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        List<BitcoinBlock> points;
        if (from != null || to != null) {
            if (from == null || to == null || from > to) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid start and end date.");
            }
            Instant start = Instant.ofEpochMilli(from);
            Instant end = Instant.ofEpochMilli(to);
            Instant now = Instant.now();
            if (end.isAfter(now)) end = now;
            if (start.isAfter(end)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected period is in the future.");
            }
            points = blocks.findByTimestampBetweenOrderByHeightAsc(start, end);
        } else {
            int window = Math.max(1, Math.min(168, hours));
            points = blocks.findByTimestampAfterOrderByHeightAsc(Instant.now().minus(window, ChronoUnit.HOURS));
        }
        return Map.of("labels", points.stream().map(BitcoinBlock::getTimestamp).toList(),
                "heights", points.stream().map(BitcoinBlock::getHeight).toList(),
                "transactions", points.stream().map(BitcoinBlock::getTransactionCount).toList(),
                "blockSize", points.stream().map(BitcoinBlock::getSize).toList(),
                "averageFeeBtc", points.stream().map(BitcoinBlock::getAverageFee).toList());
    }

    @GetMapping("/anomalies")
    public List<BitcoinTransaction> anomalies(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return transactions.findAllByOrderByTimestampDesc(PageRequest.of(Math.max(0, page), boundedSize(size)))
                .getContent().stream().filter(tx -> tx.getAnomalyLevel() != AnomalyLevel.NORMAL).toList();
    }

    @GetMapping("/anomalies/{id}")
    public BitcoinTransaction anomaly(@PathVariable String id) {
        BitcoinTransaction tx = transactions.findById(id).orElseThrow(() -> notFound("Transaction anomaly not found"));
        if (tx.getAnomalyLevel() == AnomalyLevel.NORMAL)
            throw notFound("No anomaly is recorded for this transaction");
        return tx;
    }

    @GetMapping("/alerts")
    public List<NetworkAlert> listAlerts(@RequestParam(required = false) AlertSeverity severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest request = PageRequest.of(Math.max(0, page), boundedSize(size));
        return severity == null ? alerts.findAllByOrderByDetectedAtDesc(request)
                : alerts.findBySeverityOrderByDetectedAtDesc(severity, request);
    }

    @PutMapping("/alerts/{id}/resolve")
    public NetworkAlert resolveAlert(@PathVariable long id) {
        NetworkAlert alert = alerts.findById(id).orElseThrow(() -> notFound("Alert not found"));
        alert.resolve();
        return alerts.save(alert);
    }

    @GetMapping("/monitoring/status")
    public Map<String, Object> monitoringStatus() {
        boolean databaseConnected;
        try {
            blocks.count();
            databaseConnected = true;
        } catch (RuntimeException exception) {
            databaseConnected = false;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("backend", "CONNECTED");
        status.put("database", databaseConnected ? "CONNECTED" : "DISCONNECTED");
        status.put("blockchainProvider", monitoring.getProviderStatus());
        status.put("monitoring", monitoring.isSchedulerRunning() ? "RUNNING" : "STOPPED");
        status.put("lastBlockSync", monitoring.getLastSuccessfulSync());
        status.put("lastError", monitoring.getLastError());
        status.put("websocketPath", "/ws/live");
        status.put("application", "bitcoin-traffic-monitor");
        return status;
    }

    @GetMapping("/network/mempool")
    public Map<String, Object> mempoolSnapshot() {
        return mempoolSnapshot.getSnapshot();
    }

    @GetMapping("/market/overview")
    public Map<String, Object> marketOverview() {
        return marketData.getOverviewSnapshot();
    }

    @GetMapping("/market/history")
    public Map<String, Object> marketHistory(@RequestParam(defaultValue = "bitcoin") String coinId,
            @RequestParam(defaultValue = "7") String range, @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        try {
            return marketData.getMarketHistory(coinId, range, from, to);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/market/forecast")
    public Map<String, Object> marketForecast(@RequestParam(defaultValue = "bitcoin") String coinId,
            @RequestParam(defaultValue = "30") String range, @RequestParam(defaultValue = "7") int horizonDays,
            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        try {
            return marketData.getMarketForecast(coinId, range, horizonDays, from, to);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q) {
        String query = q.trim();
        if (query.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is required");
        var tx = transactions.findById(query);
        if (tx.isPresent())
            return Map.of("source", "local", "type", "transaction", "result", tx.get());
        var block = blocks.findById(query);
        if (block.isPresent())
            return Map.of("source", "local", "type", "block", "result", block.get());
        try {
            long height = Long.parseLong(query);
            var byHeight = blocks.findFirstByHeightOrderByTimestampDesc(height);
            if (byHeight.isPresent())
                return Map.of("source", "local", "type", "block", "result", byHeight.get());
        } catch (NumberFormatException ignored) {
        }
        throw notFound("No matching transaction or block found in monitored data");
    }

    private int boundedSize(int requested) {
        return Math.max(1, Math.min(100, requested));
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}