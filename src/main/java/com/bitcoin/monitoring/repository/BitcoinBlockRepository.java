package com.bitcoin.monitoring.repository;

import com.bitcoin.monitoring.entity.BitcoinBlock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface BitcoinBlockRepository extends JpaRepository<BitcoinBlock, String> {
    Optional<BitcoinBlock> findFirstByOrderByHeightDesc();
    Optional<BitcoinBlock> findFirstByHeightOrderByTimestampDesc(long height);
    List<BitcoinBlock> findAllByOrderByHeightDesc(Pageable pageable);
    List<BitcoinBlock> findByTimestampAfterOrderByHeightAsc(Instant since);
    List<BitcoinBlock> findByTimestampBetweenOrderByHeightAsc(Instant from, Instant to);
    @Modifying
    @Transactional
    @Query("delete from BitcoinBlock b where b.timestamp < :cutoff and b.hash <> :latestHash")
    int deleteExpiredBlocks(@Param("cutoff") Instant cutoff, @Param("latestHash") String latestHash);
}