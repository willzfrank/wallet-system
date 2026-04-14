package com.example.test.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class DoTransDto {
    @NotBlank
    private String fromAccount;

    @NotBlank
    private String toAccount;

    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;
}
