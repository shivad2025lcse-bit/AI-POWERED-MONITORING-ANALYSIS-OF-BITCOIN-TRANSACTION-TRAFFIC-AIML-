CREATE DATABASE IF NOT EXISTS bitcoin_monitoring
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE bitcoin_monitoring;

CREATE TABLE IF NOT EXISTS bitcoin_blocks (
  hash VARCHAR(64) NOT NULL PRIMARY KEY,
  height BIGINT NOT NULL,
  block_timestamp TIMESTAMP(6) NOT NULL,
  transaction_count INT NOT NULL,
  size BIGINT NOT NULL,
  weight BIGINT NOT NULL,
  total_output_value DECIMAL(20,8) NOT NULL DEFAULT 0,
  total_fees DECIMAL(20,8) NOT NULL DEFAULT 0,
  average_fee DECIMAL(20,8) NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  INDEX idx_block_height (height),
  INDEX idx_block_timestamp (block_timestamp)
);

CREATE TABLE IF NOT EXISTS bitcoin_transactions (
  txid VARCHAR(64) NOT NULL PRIMARY KEY,
  block_hash VARCHAR(64) NOT NULL,
  block_height BIGINT NOT NULL,
  transaction_index INT NOT NULL,
  tx_timestamp TIMESTAMP(6) NOT NULL,
  input_count INT NOT NULL,
  output_count INT NOT NULL,
  input_value DECIMAL(20,8) NOT NULL DEFAULT 0,
  output_value DECIMAL(20,8) NOT NULL DEFAULT 0,
  fee DECIMAL(20,8) NOT NULL DEFAULT 0,
  fee_rate DECIMAL(16,4) NOT NULL DEFAULT 0,
  transaction_size BIGINT NOT NULL,
  transaction_weight BIGINT NOT NULL,
  anomaly_level VARCHAR(16) NOT NULL,
  anomaly_score DOUBLE NOT NULL,
  anomaly_reasons LONGTEXT,
  created_at TIMESTAMP(6) NOT NULL,
  INDEX idx_tx_block_hash (block_hash),
  INDEX idx_tx_block_height (block_height),
  INDEX idx_tx_timestamp (tx_timestamp),
  INDEX idx_tx_anomaly_level (anomaly_level)
);

CREATE TABLE IF NOT EXISTS network_alerts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  alert_type VARCHAR(40) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  message VARCHAR(500) NOT NULL,
  detected_at TIMESTAMP(6) NOT NULL,
  metric VARCHAR(255),
  observed_value DOUBLE NOT NULL,
  baseline_value DOUBLE NOT NULL,
  deviation_percentage DOUBLE NOT NULL,
  resolved BOOLEAN NOT NULL DEFAULT FALSE,
  INDEX idx_alert_detected_at (detected_at),
  INDEX idx_alert_severity (severity),
  INDEX idx_alert_resolved (resolved)
);