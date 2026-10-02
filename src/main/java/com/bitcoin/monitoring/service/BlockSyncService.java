package com.bitcoin.monitoring.service;

import com.bitcoin.monitoring.analytics.AnomalyAnalysisService;
import com.bitcoin.monitoring.entity.AlertSeverity;
import com.bitcoin.monitoring.entity.AnomalyLevel;
import com.bitcoin.monitoring.entity.BitcoinBlock;
import com.bitcoin.monitoring.entity.BitcoinTransaction;
import com.bitcoin.monitoring.entity.NetworkAlert;
import com.bitcoin.monitoring.provider.BlockchainDataProvider;
import com.bitcoin.monitoring.repository.BitcoinBlockRepository;
import com.bitcoin.monitoring.repository.BitcoinTransactionRepository;
import com.bitcoin.monitoring.repository.NetworkAlertRepository;
import com.bitcoin.monitoring.websocket.LiveWebSocketHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BlockSyncService {
    private static final Logger log = LoggerFactory.getLogger(BlockSyncService.class);
    private static final BigDecimal SATOSHIS_PER_BTC = new BigDecimal("100000000");
    private final BlockchainDataProvider provider;
    private final BitcoinBlockRepository blocks;
    private final BitcoinTransactionRepository transactions;
    private final NetworkAlertRepository alerts;
    private final AnomalyAnalysisService anomalyAnalysis;
    private final MonitoringState state;
    private final LiveWebSocketHandler live;
    private final ObjectMapper mapper;
    private final int maxTransactions;

    public BlockSyncService(BlockchainDataProvider provider, BitcoinBlockRepository blocks,
            BitcoinTransactionRepository transactions, NetworkAlertRepository alerts,
            AnomalyAnalysisService anomalyAnalysis, MonitoringState state, LiveWebSocketHandler live,
            ObjectMapper mapper, @Value("${blockchain.monitor.max-transactions-per-block:25}") int maxTransactions) {
        this.provider = provider;
        this.blocks = blocks;
        this.transactions = transactions;
        this.alerts = alerts;
        this.anomalyAnalysis = anomalyAnalysis;
        this.state = state;
        this.live = live;
        this.mapper = mapper;
        this.maxTransactions = Math.max(1, maxTransactions);
    }

    @Scheduled(fixedDelayString = "${blockchain.monitor.interval-ms:15000}", initialDelay = 1500)
    public void scheduledSync() {
        try { syncLatestBlock(); }
        catch (Exception exception) {
            state.failure(exception.getMessage() == null ? "Provider request failed" : exception.getMessage());
            log.warn("Blockchain synchronization failed; the dashboard remains available: {}", exception.getMessage());
        }
    }

    public void syncLatestBlock() {
        long height = provider.getLatestBlockHeight();
        String hash = provider.getBlockHashByHeight(height);
        if (blocks.existsById(hash)) {
            state.success(Instant.now());
            live.broadcast(java.util.Map.of("type", "status", "providerStatus", state.getProviderStatus(), "lastSync", state.getLastSuccessfulSync()));
            return;
        }
        JsonNode blockData = provider.getBlock(hash);
        Instant timestamp = Instant.ofEpochSecond(blockData.path("timestamp").asLong());
        int reportedCount = blockData.path("tx_count").asInt();
        JsonNode txPage = provider.getBlockTransactions(hash, 0);
        BlockSample sample = processTransactions(txPage, hash, height, timestamp);
        BitcoinBlock block = new BitcoinBlock(hash, height, timestamp, reportedCount,
            blockData.path("size").asLong(), blockData.path("weight").asLong(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal averageObservedFee = sample.count() == 0 ? BigDecimal.ZERO
            : sample.fees().divide(BigDecimal.valueOf(sample.count()), 8, RoundingMode.HALF_UP);
        block.updateObservedTransactionMetrics(sample.outputValue(), sample.fees(), averageObservedFee);
        blocks.save(block);
        state.success(Instant.now());
        log.info("Stored Bitcoin block {} with {} sampled of {} transactions", height, sample.count(), reportedCount);
        live.broadcast(java.util.Map.of("type", "block", "block", block, "transactions", sample.saved(),
                "providerStatus", state.getProviderStatus(), "lastSync", state.getLastSuccessfulSync()));
    }

        private BlockSample processTransactions(JsonNode txPage, String blockHash, long height, Instant timestamp) {
        List<BitcoinTransaction> saved = new ArrayList<>();
        BigDecimal outputValueTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        if (!txPage.isArray()) return new BlockSample(saved, outputValueTotal, feeTotal, 0);
        int count = Math.min(txPage.size(), maxTransactions);
        for (int index = 0; index < count; index++) {
            JsonNode item = txPage.get(index);
            String txid = item.path("txid").asText("");
            if (txid.isBlank()) continue;
            long size = item.path("size").asLong();
            long weight = item.path("weight").asLong();
            long feeSatoshis = item.path("fee").asLong();
            BigDecimal outputValue = sumOutputs(item.path("vout"));
            outputValueTotal = outputValueTotal.add(outputValue);
            feeTotal = feeTotal.add(toBtc(feeSatoshis));
            if (transactions.existsById(txid)) continue;
            BigDecimal feeRate = size == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(feeSatoshis)
                    .divide(BigDecimal.valueOf(item.path("vsize").asLong(Math.max(1, size))), 4, RoundingMode.HALF_UP);
            BigDecimal inputValue = sumInputs(item.path("vin"));
            List<BitcoinTransaction> baseline = transactions.findTop100ByOrderByTimestampDesc();
            AnomalyAnalysisService.Result result = anomalyAnalysis.analyze(feeRate.doubleValue(), size,
                    outputValue.doubleValue(), baseline);
            BitcoinTransaction transaction = new BitcoinTransaction(txid, blockHash, height, index, timestamp,
                    item.path("vin").isArray() ? item.path("vin").size() : 0,
                    item.path("vout").isArray() ? item.path("vout").size() : 0,
                    inputValue, outputValue, toBtc(feeSatoshis), feeRate, size, weight,
                    result.score(), result.level(), reasonsJson(result.reasons()));
            saved.add(transactions.save(transaction));
            if (result.level() == AnomalyLevel.HIGH || result.level() == AnomalyLevel.CRITICAL) {
                AlertSeverity severity = result.level() == AnomalyLevel.CRITICAL ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;
                alerts.save(new NetworkAlert("TRANSACTION_OUTLIER", severity,
                        "Unusual transaction pattern detected for " + txid.substring(0, 12) + "…",
                        "anomalyScore", result.score(), 0, result.score()));
            }
        }
        return new BlockSample(saved, outputValueTotal, feeTotal, count);
    }

    private record BlockSample(List<BitcoinTransaction> saved, BigDecimal outputValue, BigDecimal fees, int count) { }

    private BigDecimal sumOutputs(JsonNode values) {
        BigDecimal total = BigDecimal.ZERO;
        if (values.isArray()) for (JsonNode output : values) total = total.add(toBtc(output.path("value").asLong()));
        return total;
    }
    private BigDecimal sumInputs(JsonNode values) {
        BigDecimal total = BigDecimal.ZERO;
        if (values.isArray()) for (JsonNode input : values) {
            if (input.path("prevout").has("value")) total = total.add(toBtc(input.path("prevout").path("value").asLong()));
        }
        return total;
    }
    private BigDecimal toBtc(long satoshis) {
        return BigDecimal.valueOf(satoshis).divide(SATOSHIS_PER_BTC, 8, RoundingMode.HALF_UP);
    }
    private String reasonsJson(List<String> reasons) {
        try { return mapper.writeValueAsString(reasons); }
        catch (JsonProcessingException exception) { return "[]"; }
    }
}