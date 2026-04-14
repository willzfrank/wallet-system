package com.example.test.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransferResponse {
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
    private BigDecimal fromBalance;
    private BigDecimal toBalance;
}
