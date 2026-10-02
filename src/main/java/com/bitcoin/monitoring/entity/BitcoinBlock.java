package com.bitcoin.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bitcoin_blocks", indexes = {
        @Index(name = "idx_block_height", columnList = "height"),
        @Index(name = "idx_block_timestamp", columnList = "block_timestamp")
})
public class BitcoinBlock {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String hash;
    @Column(nullable = false)
    private long height;
    @Column(name = "block_timestamp", nullable = false)
    private Instant timestamp;
    private int transactionCount;
    private long size;
    private long weight;
    @Column(precision = 20, scale = 8)
    private BigDecimal totalOutputValue = BigDecimal.ZERO;
    @Column(precision = 20, scale = 8)
    private BigDecimal totalFees = BigDecimal.ZERO;
    @Column(precision = 20, scale = 8)
    private BigDecimal averageFee = BigDecimal.ZERO;
    private Instant createdAt = Instant.now();

    protected BitcoinBlock() { }

    public BitcoinBlock(String hash, long height, Instant timestamp, int transactionCount, long size, long weight,
                        BigDecimal totalOutputValue, BigDecimal totalFees, BigDecimal averageFee) {
        this.hash = hash;
        this.height = height;
        this.timestamp = timestamp;
        this.transactionCount = transactionCount;
        this.size = size;
        this.weight = weight;
        this.totalOutputValue = totalOutputValue;
        this.totalFees = totalFees;
        this.averageFee = averageFee;
    }

    public void updateObservedTransactionMetrics(BigDecimal outputValue, BigDecimal fees, BigDecimal feePerObservedTransaction) {
        this.totalOutputValue = outputValue;
        this.totalFees = fees;
        this.averageFee = feePerObservedTransaction;
    }

    public String getHash() { return hash; }
    public long getHeight() { return height; }
    public Instant getTimestamp() { return timestamp; }
    public int getTransactionCount() { return transactionCount; }
    public long getSize() { return size; }
    public long getWeight() { return weight; }
    public BigDecimal getTotalOutputValue() { return totalOutputValue; }
    public BigDecimal getTotalFees() { return totalFees; }
    public BigDecimal getAverageFee() { return averageFee; }
    public Instant getCreatedAt() { return createdAt; }
}