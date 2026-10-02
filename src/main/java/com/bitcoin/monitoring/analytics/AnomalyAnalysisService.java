package com.bitcoin.monitoring.analytics;

import com.bitcoin.monitoring.entity.AnomalyLevel;
import com.bitcoin.monitoring.entity.BitcoinTransaction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnomalyAnalysisService {
    public record Result(double score, AnomalyLevel level, List<String> reasons) { }

    public Result analyze(double feeRate, long size, double value, List<BitcoinTransaction> baseline) {
        List<String> reasons = new ArrayList<>();
        double maximumZ = 0;
        if (baseline.size() >= 8) {
            maximumZ = Math.max(maximumZ, addOutlierReason("Fee rate", feeRate,
                    baseline.stream().mapToDouble(tx -> tx.getFeeRate().doubleValue()).toArray(), "sat/vB", reasons));
            maximumZ = Math.max(maximumZ, addOutlierReason("Transaction size", size,
                    baseline.stream().mapToDouble(BitcoinTransaction::getTransactionSize).toArray(), "bytes", reasons));
            maximumZ = Math.max(maximumZ, addOutlierReason("Output value", value,
                    baseline.stream().mapToDouble(tx -> tx.getOutputValue().doubleValue()).toArray(), "BTC", reasons));
        }
        double score = Math.min(100, maximumZ * 15);
        AnomalyLevel level = maximumZ >= 5 ? AnomalyLevel.CRITICAL
                : maximumZ >= 4 ? AnomalyLevel.HIGH
                : maximumZ >= 3 ? AnomalyLevel.MEDIUM
                : maximumZ >= 2.25 ? AnomalyLevel.LOW : AnomalyLevel.NORMAL;
        if (level == AnomalyLevel.NORMAL) reasons.add(baseline.size() < 8
                ? "Insufficient history for a reliable baseline; retained as normal." : "No measured feature materially differs from the recent baseline.");
        return new Result(score, level, List.copyOf(reasons));
    }

    private double addOutlierReason(String name, double value, double[] sample, String unit, List<String> reasons) {
        double mean = 0;
        for (double point : sample) mean += point;
        mean /= sample.length;
        double variance = 0;
        for (double point : sample) variance += (point - mean) * (point - mean);
        double deviation = Math.sqrt(variance / sample.length);
                if (deviation == 0) {
                        double minimumDifference = Math.max(Math.abs(mean) * 0.1, 1e-9);
                        if (Math.abs(value - mean) < minimumDifference) return 0;
                        reasons.add(String.format("%s differs from a constant recent baseline (%.4g %s observed).", name, value, unit));
                        return 5;
                }
        double z = Math.abs(value - mean) / deviation;
        if (z >= 2.25) reasons.add(String.format("%s is %.1f standard deviations from its recent mean (%.4g %s observed).", name, z, value, unit));
        return z;
    }
}