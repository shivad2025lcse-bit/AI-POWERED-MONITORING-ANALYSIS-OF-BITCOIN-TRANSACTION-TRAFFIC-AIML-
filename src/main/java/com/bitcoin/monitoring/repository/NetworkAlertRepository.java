package com.bitcoin.monitoring.repository;

import com.bitcoin.monitoring.entity.AlertSeverity;
import com.bitcoin.monitoring.entity.NetworkAlert;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface NetworkAlertRepository extends JpaRepository<NetworkAlert, Long> {
    List<NetworkAlert> findAllByOrderByDetectedAtDesc(Pageable pageable);
    List<NetworkAlert> findBySeverityOrderByDetectedAtDesc(AlertSeverity severity, Pageable pageable);
    long countByResolvedFalse();
    @Modifying
    @Transactional
    int deleteByAlertType(String alertType);
    @Modifying
    @Transactional
    int deleteByDetectedAtBefore(Instant cutoff);
}