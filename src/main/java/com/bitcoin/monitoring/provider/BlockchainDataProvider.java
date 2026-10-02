package com.bitcoin.monitoring.provider;

import com.fasterxml.jackson.databind.JsonNode;

public interface BlockchainDataProvider {
    long getLatestBlockHeight();
    String getBlockHashByHeight(long height);
    JsonNode getBlock(String hash);
    JsonNode getBlockTransactions(String hash, int startIndex);
    JsonNode getTransaction(String txid);
    JsonNode getMempoolInfo();
    JsonNode getRecommendedFees();
    JsonNode getRecentMempoolTransactions();
}