package com.bitcoin.monitoring.repository;

import com.bitcoin.monitoring.entity.AnomalyLevel;
import com.bitcoin.monitoring.entity.BitcoinTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface BitcoinTransactionRepository extends JpaRepository<BitcoinTransaction, String> {
    Page<BitcoinTransaction> findAllByOrderByTimestampDesc(Pageable pageable);
    Page<BitcoinTransaction> findByTxidContainingIgnoreCaseOrderByTimestampDesc(String query, Pageable pageable);
    Page<BitcoinTransaction> findByAnomalyLevelOrderByTimestampDesc(AnomalyLevel level, Pageable pageable);
    List<BitcoinTransaction> findTop100ByOrderByTimestampDesc();
    List<BitcoinTransaction> findByTimestampAfterOrderByTimestampAsc(Instant since);
    long countByTimestampAfter(Instant since);
    long countByAnomalyLevel(AnomalyLevel level);
    long countByAnomalyLevelNot(AnomalyLevel level);
    BigDecimal findAverageFeeRateByTimestampAfter(Instant since);
    @Modifying
    @Transactional
    int deleteByTimestampBeforeAndBlockHeightLessThan(Instant cutoff, long latestHeight);
}