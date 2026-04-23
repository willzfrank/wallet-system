package com.example.test.service;

import com.example.test.model.TransferAuditLog;
import com.example.test.repo.TransferAuditLogRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {
    private final TransferAuditLogRepo transferAuditLogRepo;

    public AuditLogService(TransferAuditLogRepo transferAuditLogRepo) {
        this.transferAuditLogRepo = transferAuditLogRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(String transactionReference, String eventType, String details) {
        transferAuditLogRepo.save(new TransferAuditLog(transactionReference, eventType, details));
    }
}
