package com.bitcoin.monitoring.scheduler;

import com.bitcoin.monitoring.repository.BitcoinBlockRepository;
import com.bitcoin.monitoring.repository.BitcoinTransactionRepository;
import com.bitcoin.monitoring.repository.NetworkAlertRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);
    private final BitcoinBlockRepository blocks;
    private final BitcoinTransactionRepository transactions;
    private final NetworkAlertRepository alerts;
    private final int retentionDays;

    public RetentionScheduler(BitcoinBlockRepository blocks, BitcoinTransactionRepository transactions,
                              NetworkAlertRepository alerts, @Value("${monitoring.retention.days:30}") int retentionDays) {
        this.blocks = blocks;
        this.transactions = transactions;
        this.alerts = alerts;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 86_400_000)
    public void deleteExpiredData() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        Optional<com.bitcoin.monitoring.entity.BitcoinBlock> latest = blocks.findFirstByOrderByHeightDesc();
        int removedTransactions = latest.map(block -> transactions.deleteByTimestampBeforeAndBlockHeightLessThan(cutoff, block.getHeight()))
            .orElseGet(() -> transactions.deleteByTimestampBeforeAndBlockHeightLessThan(cutoff, 0));
        int removedBlocks = latest.map(block -> blocks.deleteExpiredBlocks(cutoff, block.getHash()))
            .orElseGet(() -> 0);
        int removedAlerts = alerts.deleteByDetectedAtBefore(cutoff);
        log.info("Retention removed {} transactions, {} blocks and {} alerts older than {} days",
                removedTransactions, removedBlocks, removedAlerts, retentionDays);
    }
}