package com.example.test.service;

import com.example.test.repo.TransactionHistoryRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class IdempotencyCleanupJob {

    private final TransactionHistoryRepo transactionHistoryRepo;
    private final long retentionDays;

    public IdempotencyCleanupJob(
            TransactionHistoryRepo transactionHistoryRepo,
            @Value("${wallet.idempotency.retention-days:7}") long retentionDays
    ) {
        this.transactionHistoryRepo = transactionHistoryRepo;
        this.retentionDays = retentionDays;
    }

    // Keeps idempotency table growth controlled by removing old SUCCESS records.
    @Scheduled(cron = "${wallet.idempotency.cleanup-cron:0 0 2 * * *}")
    public void cleanupOldIdempotencyRecords() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        transactionHistoryRepo.deleteByStatusAndCreatedAtBefore("SUCCESS", cutoff);
    }
}
