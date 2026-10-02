package com.bitcoin.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bitcoin_transactions", indexes = {
        @Index(name = "idx_tx_block_hash", columnList = "block_hash"),
        @Index(name = "idx_tx_block_height", columnList = "block_height"),
        @Index(name = "idx_tx_timestamp", columnList = "tx_timestamp"),
        @Index(name = "idx_tx_anomaly_level", columnList = "anomaly_level")
})
public class BitcoinTransaction {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String txid;
    @Column(name = "block_hash", length = 64, nullable = false)
    private String blockHash;
    @Column(name = "block_height", nullable = false)
    private long blockHeight;
    private int transactionIndex;
    @Column(name = "tx_timestamp", nullable = false)
    private Instant timestamp;
    private int inputCount;
    private int outputCount;
    @Column(precision = 20, scale = 8)
    private BigDecimal inputValue = BigDecimal.ZERO;
    @Column(precision = 20, scale = 8)
    private BigDecimal outputValue = BigDecimal.ZERO;
    @Column(precision = 20, scale = 8)
    private BigDecimal fee = BigDecimal.ZERO;
    @Column(precision = 16, scale = 4)
    private BigDecimal feeRate = BigDecimal.ZERO;
    private long transactionSize;
    private long transactionWeight;
    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly_level", nullable = false)
    private AnomalyLevel anomalyLevel = AnomalyLevel.NORMAL;
    private double anomalyScore;
    @Lob
    private String anomalyReasons = "[]";
    private Instant createdAt = Instant.now();

    protected BitcoinTransaction() { }

    public BitcoinTransaction(String txid, String blockHash, long blockHeight, int transactionIndex,
                              Instant timestamp, int inputCount, int outputCount, BigDecimal inputValue,
                              BigDecimal outputValue, BigDecimal fee, BigDecimal feeRate, long transactionSize,
                              long transactionWeight, double anomalyScore, AnomalyLevel anomalyLevel, String anomalyReasons) {
        this.txid = txid;
        this.blockHash = blockHash;
        this.blockHeight = blockHeight;
        this.transactionIndex = transactionIndex;
        this.timestamp = timestamp;
        this.inputCount = inputCount;
        this.outputCount = outputCount;
        this.inputValue = inputValue;
        this.outputValue = outputValue;
        this.fee = fee;
        this.feeRate = feeRate;
        this.transactionSize = transactionSize;
        this.transactionWeight = transactionWeight;
        this.anomalyScore = anomalyScore;
        this.anomalyLevel = anomalyLevel;
        this.anomalyReasons = anomalyReasons;
    }

    public String getTxid() { return txid; }
    public String getBlockHash() { return blockHash; }
    public long getBlockHeight() { return blockHeight; }
    public int getTransactionIndex() { return transactionIndex; }
    public Instant getTimestamp() { return timestamp; }
    public int getInputCount() { return inputCount; }
    public int getOutputCount() { return outputCount; }
    public BigDecimal getInputValue() { return inputValue; }
    public BigDecimal getOutputValue() { return outputValue; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getFeeRate() { return feeRate; }
    public long getTransactionSize() { return transactionSize; }
    public long getTransactionWeight() { return transactionWeight; }
    public AnomalyLevel getAnomalyLevel() { return anomalyLevel; }
    public double getAnomalyScore() { return anomalyScore; }
    public String getAnomalyReasons() { return anomalyReasons; }
    public Instant getCreatedAt() { return createdAt; }
}