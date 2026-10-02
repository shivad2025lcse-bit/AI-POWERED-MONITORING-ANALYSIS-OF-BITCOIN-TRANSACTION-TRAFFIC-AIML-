package com.bitcoin.monitoring.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bitcoin.monitoring.entity.AnomalyLevel;
import com.bitcoin.monitoring.entity.BitcoinTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnomalyAnalysisServiceTest {
    private final AnomalyAnalysisService service = new AnomalyAnalysisService();

    @Test
    void insufficientHistoryDoesNotInventAnomaly() {
        AnomalyAnalysisService.Result result = service.analyze(12, 200, 0.1, List.of());
        assertEquals(AnomalyLevel.NORMAL, result.level());
        assertEquals(0, result.score());
        assertTrue(result.reasons().get(0).contains("Insufficient history"));
    }

    @Test
    void scoresFeatureThatDeviatesFromRollingSample() {
        List<BitcoinTransaction> baseline = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> new BitcoinTransaction("tx" + index, "block", index, index, Instant.now(),
                        1, 2, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                        BigDecimal.valueOf(2 + index % 2), 200, 800, 0, AnomalyLevel.NORMAL, "[]"))
                .toList();

        AnomalyAnalysisService.Result result = service.analyze(100, 200, 1, baseline);

        assertTrue(result.score() > 0);
        assertTrue(result.level().ordinal() >= AnomalyLevel.MEDIUM.ordinal());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("Fee rate")));
    }

    @Test
    void detectsDeviationFromConstantBaseline() {
        List<BitcoinTransaction> baseline = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new BitcoinTransaction("flat" + index, "block", index, index, Instant.now(),
                        1, 2, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                        BigDecimal.ONE, 200, 800, 0, AnomalyLevel.NORMAL, "[]"))
                .toList();

        AnomalyAnalysisService.Result result = service.analyze(2, 200, 1, baseline);

        assertEquals(AnomalyLevel.CRITICAL, result.level());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("constant recent baseline")));
    }
}