package com.example.test.controller;

import com.example.test.dto.BalanceResponse;
import com.example.test.dto.CreateUserAccountRequest;
import com.example.test.dto.CreateUserAccountResponse;
import com.example.test.dto.DoTransDto;
import com.example.test.dto.TransferResponse;
import com.example.test.service.ServiceCall;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final ServiceCall serviceCall;

    public WalletController(ServiceCall serviceCall) {
        this.serviceCall = serviceCall;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUserAccountResponse createUserAndAccount(@Valid @RequestBody CreateUserAccountRequest request) {
        return serviceCall.createUserAndAccount(request);
    }

    @PostMapping("/transfer")
    public TransferResponse transfer(@Valid @RequestBody DoTransDto request) {
        return serviceCall.doIntraTransfer(request);
    }

    @GetMapping("/accounts/{accountNumber}/balance")
    public BalanceResponse getBalance(@PathVariable String accountNumber) {
        return serviceCall.getBalance(accountNumber);
    }
}
