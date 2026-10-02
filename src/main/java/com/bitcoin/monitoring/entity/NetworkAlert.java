package com.bitcoin.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "network_alerts", indexes = {
        @Index(name = "idx_alert_detected_at", columnList = "detected_at"),
        @Index(name = "idx_alert_severity", columnList = "severity"),
        @Index(name = "idx_alert_resolved", columnList = "resolved")
})
public class NetworkAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String alertType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertSeverity severity;
    @Column(nullable = false, length = 500)
    private String message;
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();
    private String metric;
    private double observedValue;
    private double baselineValue;
    private double deviationPercentage;
    private boolean resolved;

    protected NetworkAlert() { }
    public NetworkAlert(String alertType, AlertSeverity severity, String message, String metric,
                        double observedValue, double baselineValue, double deviationPercentage) {
        this.alertType = alertType;
        this.severity = severity;
        this.message = message;
        this.metric = metric;
        this.observedValue = observedValue;
        this.baselineValue = baselineValue;
        this.deviationPercentage = deviationPercentage;
    }
    public Long getId() { return id; }
    public String getAlertType() { return alertType; }
    public AlertSeverity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public Instant getDetectedAt() { return detectedAt; }
    public String getMetric() { return metric; }
    public double getObservedValue() { return observedValue; }
    public double getBaselineValue() { return baselineValue; }
    public double getDeviationPercentage() { return deviationPercentage; }
    public boolean isResolved() { return resolved; }
    public void resolve() { this.resolved = true; }
}