package com.example.test.repo;

import com.example.test.model.TransactionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionHistoryRepo extends JpaRepository<TransactionHistory, Long> {
}
