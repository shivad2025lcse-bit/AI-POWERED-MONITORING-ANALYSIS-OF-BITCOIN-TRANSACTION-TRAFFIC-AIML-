package com.bitcoin.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bitcoin.monitoring.provider.BlockchainDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MempoolSnapshotServiceTest {
    @Test
    void refreshPublishesCurrentMempoolFeeAndRecentTransactionData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StubProvider provider = new StubProvider(
                mapper.readTree("{\"count\":42,\"vsize\":1200}"),
                mapper.readTree("{\"fastestFee\":12}"), mapper.readTree("[{\"txid\":\"abc\"}]"));
        MempoolSnapshotService service = new MempoolSnapshotService(provider);

        service.refresh();

        assertEquals("CONNECTED", service.getSnapshot().get("status"));
        assertEquals(42, ((JsonNode) service.getSnapshot().get("mempool")).path("count").asInt());
        assertEquals(12, ((JsonNode) service.getSnapshot().get("fees")).path("fastestFee").asInt());
        assertEquals("abc", ((JsonNode) service.getSnapshot().get("recentTransactions")).get(0).path("txid").asText());
    }

    @Test
    void refreshFailureReportsUnavailableWithoutInventingSnapshotData() {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("provider unavailable");
        MempoolSnapshotService service = new MempoolSnapshotService(provider);

        service.refresh();

        assertEquals("DISCONNECTED", service.getSnapshot().get("status"));
        assertEquals("provider unavailable", service.getSnapshot().get("error"));
        assertNull(service.getSnapshot().get("mempool"));
    }

    @Test
    void refreshFailureRetainsLastGoodSnapshotAndMarksItDegraded() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StubProvider provider = new StubProvider(mapper.readTree("{\"count\":42}"),
            mapper.readTree("{\"fastestFee\":12}"), mapper.readTree("[]"));
        MempoolSnapshotService service = new MempoolSnapshotService(provider);

        service.refresh();
        provider.failure = new IllegalStateException("provider unavailable");
        service.refresh();

        assertEquals("DEGRADED", service.getSnapshot().get("status"));
        assertEquals(42, ((JsonNode) service.getSnapshot().get("mempool")).path("count").asInt());
        assertEquals("provider unavailable", service.getSnapshot().get("error"));
    }

    private static class StubProvider implements BlockchainDataProvider {
        private final JsonNode mempool;
        private final JsonNode fees;
        private final JsonNode transactions;
        private RuntimeException failure;

        StubProvider() {
            this(null, null, null);
        }

        StubProvider(JsonNode mempool, JsonNode fees, JsonNode transactions) {
            this.mempool = mempool;
            this.fees = fees;
            this.transactions = transactions;
        }

        @Override public long getLatestBlockHeight() { throw new UnsupportedOperationException(); }
        @Override public String getBlockHashByHeight(long height) { throw new UnsupportedOperationException(); }
        @Override public JsonNode getBlock(String hash) { throw new UnsupportedOperationException(); }
        @Override public JsonNode getBlockTransactions(String hash, int startIndex) { throw new UnsupportedOperationException(); }
        @Override public JsonNode getTransaction(String txid) { throw new UnsupportedOperationException(); }
        @Override public JsonNode getMempoolInfo() { if (failure != null) throw failure; return mempool; }
        @Override public JsonNode getRecommendedFees() { return fees; }
        @Override public JsonNode getRecentMempoolTransactions() { return transactions; }
    }
}