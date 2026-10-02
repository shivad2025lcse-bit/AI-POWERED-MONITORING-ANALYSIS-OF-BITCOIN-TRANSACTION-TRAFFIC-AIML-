package com.bitcoin.monitoring.service;

import com.bitcoin.monitoring.provider.BlockchainDataProvider;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MempoolSnapshotService {
    private final BlockchainDataProvider provider;
    private final AtomicReference<Map<String, Object>> snapshot = new AtomicReference<>(initialSnapshot());
    private volatile Map<String, Object> lastGoodSnapshot = initialSnapshot();

    public MempoolSnapshotService(BlockchainDataProvider provider) {
        this.provider = provider;
    }

    @Scheduled(fixedDelayString = "${blockchain.monitor.mempool-interval-ms:30000}", initialDelay = 5000)
    public void scheduledRefresh() {
        refresh();
    }

    public void refresh() {
        try {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("status", "CONNECTED");
            current.put("lastUpdated", Instant.now());
            current.put("mempool", provider.getMempoolInfo());
            current.put("fees", provider.getRecommendedFees());
            current.put("recentTransactions", provider.getRecentMempoolTransactions());
            current.put("error", "");
            snapshot.set(Collections.unmodifiableMap(current));
            lastGoodSnapshot = current;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "Mempool provider is temporarily unavailable" : exception.getMessage();
            if (lastGoodSnapshot != null && lastGoodSnapshot.get("mempool") != null) {
                Map<String, Object> degraded = new LinkedHashMap<>(lastGoodSnapshot);
                degraded.put("status", "DEGRADED");
                degraded.put("error", message);
                snapshot.set(Collections.unmodifiableMap(degraded));
                return;
            }

            Map<String, Object> unavailable = new LinkedHashMap<>();
            unavailable.put("status", "DISCONNECTED");
            unavailable.put("lastUpdated", null);
            unavailable.put("mempool", null);
            unavailable.put("fees", null);
            unavailable.put("recentTransactions", List.of());
            unavailable.put("error", message);
            snapshot.set(Collections.unmodifiableMap(unavailable));
        }
    }

    public Map<String, Object> getSnapshot() {
        return snapshot.get();
    }

    private static Map<String, Object> initialSnapshot() {
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("status", "CONNECTING");
        initial.put("lastUpdated", null);
        initial.put("mempool", null);
        initial.put("fees", null);
        initial.put("recentTransactions", List.of());
        initial.put("error", "");
        return Collections.unmodifiableMap(initial);
    }

}