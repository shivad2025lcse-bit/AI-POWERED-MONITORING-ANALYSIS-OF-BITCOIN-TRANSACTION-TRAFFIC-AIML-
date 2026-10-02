package com.bitcoin.monitoring.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class MonitoringState {
    private final AtomicReference<String> providerStatus = new AtomicReference<>("CONNECTING");
    private volatile Instant lastSuccessfulSync;
    private volatile String lastError;
    private volatile boolean schedulerRunning = true;

    public void success(Instant time) {
        lastSuccessfulSync = time;
        lastError = null;
        providerStatus.set("CONNECTED");
    }
    public void failure(String message) {
        lastError = message;
        providerStatus.set(lastSuccessfulSync == null ? "DISCONNECTED" : "DEGRADED");
    }
    public String getProviderStatus() { return providerStatus.get(); }
    public Instant getLastSuccessfulSync() { return lastSuccessfulSync; }
    public String getLastError() { return lastError; }
    public boolean isSchedulerRunning() { return schedulerRunning; }
}