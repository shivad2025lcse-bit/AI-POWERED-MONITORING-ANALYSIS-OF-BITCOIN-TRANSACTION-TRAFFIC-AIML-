package com.bitcoin.monitoring.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bitcoin.monitoring.repository.BitcoinBlockRepository;
import com.bitcoin.monitoring.repository.BitcoinTransactionRepository;
import com.bitcoin.monitoring.repository.NetworkAlertRepository;
import org.junit.jupiter.api.Test;

class LegacySampleCleanupTest {
    @Test
    void removesOnlyKnownGeneratedRowsAndDemoAlerts() {
        BitcoinBlockRepository blocks = mock(BitcoinBlockRepository.class);
        BitcoinTransactionRepository transactions = mock(BitcoinTransactionRepository.class);
        NetworkAlertRepository alerts = mock(NetworkAlertRepository.class);

        new LegacySampleCleanup(blocks, transactions, alerts).removeGeneratedSampleData();

        verify(blocks).deleteAllById(anyList());
        verify(transactions).deleteAllById(anyList());
        verify(alerts).deleteByAlertType("SEED_DEMO");
    }
}