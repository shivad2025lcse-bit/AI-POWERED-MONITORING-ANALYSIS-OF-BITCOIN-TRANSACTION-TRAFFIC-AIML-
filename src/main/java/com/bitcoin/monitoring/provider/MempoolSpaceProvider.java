package com.bitcoin.monitoring.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MempoolSpaceProvider implements BlockchainDataProvider {
    private static final Logger log = LoggerFactory.getLogger(MempoolSpaceProvider.class);
    private final RestClient client;
    private final RestClient fallbackClient;

    public MempoolSpaceProvider(
            @Qualifier("blockchainRestClient") RestClient blockchainRestClient,
            @Qualifier("blockchainFallbackRestClient") RestClient blockchainFallbackRestClient) {
        this.client = blockchainRestClient;
        this.fallbackClient = blockchainFallbackRestClient;
    }

    @Override
    public long getLatestBlockHeight() {
        return retry(provider -> {
            String response = provider.get().uri("/blocks/tip/height").retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException("Provider returned no block height");
            return Long.parseLong(response.trim());
        });
    }

    @Override
    public String getBlockHashByHeight(long height) {
        return retry(provider -> {
            String response = provider.get().uri("/block-height/{height}", height).retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException("Provider returned no block hash");
            return response.trim();
        });
    }

    @Override
    public JsonNode getBlock(String hash) {
        return retry(provider -> provider.get().uri("/block/{hash}", hash).retrieve().body(JsonNode.class));
    }

    @Override
    public JsonNode getBlockTransactions(String hash, int startIndex) {
        return retry(provider -> provider.get().uri("/block/{hash}/txs/{start}", hash, startIndex)
                .retrieve().body(JsonNode.class));
    }

    @Override
    public JsonNode getTransaction(String txid) {
        return retry(provider -> provider.get().uri("/tx/{txid}", txid).retrieve().body(JsonNode.class));
    }

    @Override
    public JsonNode getMempoolInfo() {
        return retry(provider -> provider.get().uri("/mempool").retrieve().body(JsonNode.class));
    }

    @Override
    public JsonNode getRecommendedFees() {
        return retry(provider -> provider.get().uri("/v1/fees/recommended").retrieve().body(JsonNode.class));
    }

    @Override
    public JsonNode getRecentMempoolTransactions() {
        return retry(provider -> provider.get().uri("/mempool/recent").retrieve().body(JsonNode.class));
    }

    private <T> T retry(Function<RestClient, T> request) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                T response = request.apply(client);
                logFallback(false);
                return response;
            } catch (RuntimeException exception) {
                log.debug("Primary blockchain provider failed: {}", exception.getMessage());
            }
            try {
                T response = request.apply(fallbackClient);
                logFallback(true);
                return response;
            } catch (RuntimeException exception) {
                failure = exception;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(500L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Blockchain provider retry interrupted", interrupted);
                }
            }
        }
        log.warn("Primary and fallback blockchain providers failed: {}",
                failure == null ? "unknown" : failure.getMessage());
        throw new IllegalStateException("Blockchain providers are temporarily unavailable", failure);
    }

    private void logFallback(boolean usedFallback) {
        if (usedFallback) log.info("Using Blockstream fallback for blockchain data");
    }
}