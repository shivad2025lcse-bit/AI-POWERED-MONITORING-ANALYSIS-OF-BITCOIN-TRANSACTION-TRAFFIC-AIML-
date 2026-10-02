package com.bitcoin.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BitcoinMonitoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(BitcoinMonitoringApplication.class, args);
    }
}