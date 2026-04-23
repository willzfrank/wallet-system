package com.example.test.repo;

import com.example.test.model.TransactionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionHistoryRepo extends JpaRepository<TransactionHistory, Long> {
    Optional<TransactionHistory> findByTransactionReference(String transactionReference);
}
