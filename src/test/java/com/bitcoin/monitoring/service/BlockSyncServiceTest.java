package com.bitcoin.monitoring.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bitcoin.monitoring.analytics.AnomalyAnalysisService;
import com.bitcoin.monitoring.provider.BlockchainDataProvider;
import com.bitcoin.monitoring.repository.BitcoinBlockRepository;
import com.bitcoin.monitoring.repository.BitcoinTransactionRepository;
import com.bitcoin.monitoring.repository.NetworkAlertRepository;
import com.bitcoin.monitoring.websocket.LiveWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockSyncServiceTest {
    @Mock BlockchainDataProvider provider;
    @Mock BitcoinBlockRepository blocks;
    @Mock BitcoinTransactionRepository transactions;
    @Mock NetworkAlertRepository alerts;
    @Mock AnomalyAnalysisService anomalyAnalysis;
    @Mock LiveWebSocketHandler live;
    @InjectMocks MonitoringState state;

    @Test
    void duplicateLatestBlockIsNotFetchedOrInsertedAgain() {
        when(provider.getLatestBlockHeight()).thenReturn(900_000L);
        when(provider.getBlockHashByHeight(900_000L)).thenReturn("existing-hash");
        when(blocks.existsById("existing-hash")).thenReturn(true);
        BlockSyncService service = new BlockSyncService(provider, blocks, transactions, alerts,
                anomalyAnalysis, state, live, new ObjectMapper(), 25);

        service.syncLatestBlock();

        verify(blocks, never()).save(org.mockito.ArgumentMatchers.any());
        verify(provider, never()).getBlock("existing-hash");
        verify(provider, never()).getBlockTransactions("existing-hash", 0);
    }
}