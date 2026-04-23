package com.example.test.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, unique = true, length = 100)
    private String transactionReference;

    @Column(nullable = false, length = 20)
    private String status;

    @Column
    private BigDecimal fromBalanceAfter;

    @Column
    private BigDecimal toBalanceAfter;
}
