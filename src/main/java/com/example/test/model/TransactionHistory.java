package com.example.test.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transaction_history")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionHistory implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fromAccount;

    @Column(nullable = false)
    private String toAccount;

    @Column(nullable = false)
    private BigDecimal amount;

    // DB clock source: avoids relying on app server time for ordering/audit.
    @Column(
            nullable = false,
            insertable = false,
            updatable = false,
            columnDefinition = "timestamp default current_timestamp"
    )
    private Instant createdAt;

    @Column(nullable = false, unique = true, length = 100)
    private String transactionReference;

    @Column(nullable = false, length = 20)
    private String status;

    @Column
    private BigDecimal fromBalanceAfter;

    @Column
    private BigDecimal toBalanceAfter;

    @Column(length = 300)
    private String failureReason;
}
