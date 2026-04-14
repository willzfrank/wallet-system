package com.example.test.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateUserAccountResponse {
    private Long userId;
    private String email;
    private String accountNumber;
    private BigDecimal balance;
}
