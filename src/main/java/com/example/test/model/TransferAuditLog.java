package com.example.test.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "transfer_audit_logs")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TransferAuditLog implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, updatable = false)
    private String transactionReference;

    @Column(nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(nullable = false, length = 500, updatable = false)
    private String details;

    @Column(
            nullable = false,
            insertable = false,
            updatable = false,
            columnDefinition = "timestamp default current_timestamp"
    )
    private Instant createdAt;

    public TransferAuditLog(String transactionReference, String eventType, String details) {
        this.transactionReference = transactionReference;
        this.eventType = eventType;
        this.details = details;
    }

    @PreUpdate
    @PreRemove
    public void blockMutation() {
        throw new UnsupportedOperationException("TransferAuditLog is immutable and append-only.");
    }
}
