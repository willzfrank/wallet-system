package com.example.test.repo;

import com.example.test.model.WalletBalance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletBalanceRepo extends JpaRepository<WalletBalance, Long> {
}
