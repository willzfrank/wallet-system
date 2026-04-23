package com.example.test.service;

import com.example.test.model.TransactionHistory;
import com.example.test.repo.TransactionHistoryRepo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class TransferIdempotencyService {
    private final TransactionHistoryRepo transactionHistoryRepo;

    public TransferIdempotencyService(TransactionHistoryRepo transactionHistoryRepo) {
        this.transactionHistoryRepo = transactionHistoryRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservationResult reserve(String txRef, String fromAccount, String toAccount, BigDecimal amount) {
        TransactionHistory history = new TransactionHistory();
        history.setFromAccount(fromAccount);
        history.setToAccount(toAccount);
        history.setAmount(amount);
        history.setTransactionReference(txRef);
        history.setStatus("PENDING");
        try {
            TransactionHistory saved = transactionHistoryRepo.saveAndFlush(history);
            return new ReservationResult(saved, true);
        } catch (DataIntegrityViolationException ex) {
            TransactionHistory existing = transactionHistoryRepo.findByTransactionReference(txRef).orElseThrow(() -> ex);
            return new ReservationResult(existing, false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionHistory markSuccess(String txRef, BigDecimal fromBalanceAfter, BigDecimal toBalanceAfter) {
        TransactionHistory history = transactionHistoryRepo.findByTransactionReference(txRef)
                .orElseThrow(() -> new IllegalStateException("Missing transaction reference: " + txRef));
        history.setStatus("SUCCESS");
        history.setFromBalanceAfter(fromBalanceAfter);
        history.setToBalanceAfter(toBalanceAfter);
        history.setFailureReason(null);
        return transactionHistoryRepo.save(history);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String txRef, String reason) {
        Optional<TransactionHistory> optional = transactionHistoryRepo.findByTransactionReference(txRef);
        if (optional.isEmpty()) {
            return;
        }
        TransactionHistory history = optional.get();
        history.setStatus("FAILED");
        history.setFailureReason(reason);
        transactionHistoryRepo.save(history);
    }

    public record ReservationResult(TransactionHistory history, boolean reservedNew) {}
}
