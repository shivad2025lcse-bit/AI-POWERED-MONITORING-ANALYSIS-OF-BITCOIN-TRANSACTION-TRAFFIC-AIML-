package com.bitcoin.monitoring.service;

import com.bitcoin.monitoring.repository.BitcoinBlockRepository;
import com.bitcoin.monitoring.repository.BitcoinTransactionRepository;
import com.bitcoin.monitoring.repository.NetworkAlertRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class LegacySampleCleanup {
    private final BitcoinBlockRepository blocks;
    private final BitcoinTransactionRepository transactions;
    private final NetworkAlertRepository alerts;

    public LegacySampleCleanup(BitcoinBlockRepository blocks,
                               BitcoinTransactionRepository transactions,
                               NetworkAlertRepository alerts) {
        this.blocks = blocks;
        this.transactions = transactions;
        this.alerts = alerts;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void removeGeneratedSampleData() {
        List<String> blockHashes = new ArrayList<>();
        List<String> transactionIds = new ArrayList<>();
        for (int offset = 0; offset < 6; offset++) {
            long height = 888_420L - offset;
            blockHashes.add(String.format("%064x", height * 37L + offset * 13L));
            int transactionCount = 5 + (offset % 3);
            for (int transactionIndex = 0; transactionIndex < transactionCount; transactionIndex++) {
                long txid = (height * 97L) + (transactionIndex * 131L) + offset * 17L;
                transactionIds.add(String.format("%064x", txid));
            }
        }
        transactions.deleteAllById(transactionIds);
        blocks.deleteAllById(blockHashes);
        alerts.deleteByAlertType("SEED_DEMO");
    }
}