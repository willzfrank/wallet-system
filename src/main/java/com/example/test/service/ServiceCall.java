package com.example.test.service;

import com.example.test.dto.BalanceResponse;
import com.example.test.dto.CreateUserAccountRequest;
import com.example.test.dto.CreateUserAccountResponse;
import com.example.test.dto.DoTransDto;
import com.example.test.dto.TransferResponse;

public interface ServiceCall {

    CreateUserAccountResponse createUserAndAccount(CreateUserAccountRequest request);

    TransferResponse doIntraTransfer(DoTransDto request);

    BalanceResponse getBalance(String accountNumber);

}
