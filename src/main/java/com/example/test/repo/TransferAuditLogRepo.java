package com.example.test.repo;

import com.example.test.model.TransferAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferAuditLogRepo extends JpaRepository<TransferAuditLog, Long> {
}
