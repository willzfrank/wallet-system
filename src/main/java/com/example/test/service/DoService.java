package com.example.test.service;

import com.example.test.dto.BalanceResponse;
import com.example.test.dto.CreateUserAccountRequest;
import com.example.test.dto.CreateUserAccountResponse;
import com.example.test.dto.DoTransDto;
import com.example.test.dto.TransferResponse;
import com.example.test.exception.AccountNotFoundException;
import com.example.test.exception.AccountNumberGenerationException;
import com.example.test.exception.DuplicateUserException;
import com.example.test.exception.InsufficientFundsException;
import com.example.test.exception.InvalidTransferException;
import com.example.test.model.Account;
import com.example.test.model.TransactionHistory;
import com.example.test.model.User;
import com.example.test.model.WalletBalance;
import com.example.test.repo.AccountRepo;
import com.example.test.repo.TransactionHistoryRepo;
import com.example.test.repo.UserRepo;
import com.example.test.repo.WalletBalanceRepo;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class DoService implements ServiceCall {

    private final UserRepo userRepo;
    private final AccountRepo accountRepo;
    private final WalletBalanceRepo walletBalanceRepo;
    private final TransactionHistoryRepo transactionHistoryRepo;

    public DoService(
            UserRepo userRepo,
            AccountRepo accountRepo,
            WalletBalanceRepo walletBalanceRepo,
            TransactionHistoryRepo transactionHistoryRepo
    ) {
        this.userRepo = userRepo;
        this.accountRepo = accountRepo;
        this.walletBalanceRepo = walletBalanceRepo;
        this.transactionHistoryRepo = transactionHistoryRepo;
    }


    @Override
    @Transactional
    @Retryable(
            retryFor = DataIntegrityViolationException.class,
            maxAttempts = 5,
            backoff = @Backoff(delay = 20, multiplier = 2.0)
    )
    public CreateUserAccountResponse createUserAndAccount(CreateUserAccountRequest request) {
        if (userRepo.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateUserException("User already exists for email: " + request.getEmail());
        }

        BigDecimal initialBalance = request.getInitialBalance() == null ? BigDecimal.ZERO : request.getInitialBalance();
        if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidTransferException("Initial balance cannot be negative");
        }

        User user = new User();
        user.setEmail(request.getEmail());

        WalletBalance walletBalance = new WalletBalance();
        walletBalance.setAmount(initialBalance);
        walletBalanceRepo.save(walletBalance);

        Account account = new Account();
        account.setAccountNumber(generateAccountNumber());
        account.setUser(user);
        account.setWalletBalance(walletBalance);

        user.getAccounts().add(account);
        User savedUser = userRepo.saveAndFlush(user);
        Account savedAccount = savedUser.getAccounts().get(0);

        return new CreateUserAccountResponse(
                savedUser.getId(),
                savedUser.getEmail(),
                savedAccount.getAccountNumber(),
                savedAccount.getWalletBalance().getAmount()
        );
    }

    @Recover
    public CreateUserAccountResponse recoverCreateUserAndAccount(
            DataIntegrityViolationException ex,
            CreateUserAccountRequest request
    ) {
        if (userRepo.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateUserException("User already exists for email: " + request.getEmail());
        }
        throw new AccountNumberGenerationException("Could not allocate a unique account number. Please retry.");
    }

    @Override
    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2.0)
    )
    public TransferResponse doIntraTransfer(DoTransDto request) {
        if (request.getFromAccount().equals(request.getToAccount())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be greater than zero");
        }

        Account fromAccount = accountRepo.findByAccountNumber(request.getFromAccount())
                .orElseThrow(() -> new AccountNotFoundException("Source account not found: " + request.getFromAccount()));
        Account toAccount = accountRepo.findByAccountNumber(request.getToAccount())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found: " + request.getToAccount()));

        WalletBalance fromBalance = fromAccount.getWalletBalance();
        WalletBalance toBalance = toAccount.getWalletBalance();

        if (fromBalance.getAmount().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account: " + request.getFromAccount());
        }

        fromBalance.setAmount(fromBalance.getAmount().subtract(request.getAmount()));
        toBalance.setAmount(toBalance.getAmount().add(request.getAmount()));

        walletBalanceRepo.save(fromBalance);
        walletBalanceRepo.save(toBalance);

        TransactionHistory history = new TransactionHistory();
        history.setFromAccount(request.getFromAccount());
        history.setToAccount(request.getToAccount());
        history.setAmount(request.getAmount());
        history.setCreatedAt(LocalDateTime.now());
        transactionHistoryRepo.save(history);

        return new TransferResponse(
                request.getFromAccount(),
                request.getToAccount(),
                request.getAmount(),
                fromBalance.getAmount(),
                toBalance.getAmount()
        );
    }

    @Override
    public BalanceResponse getBalance(String accountNumber) {
        Account account = accountRepo.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
        return new BalanceResponse(accountNumber, account.getWalletBalance().getAmount());
    }

    private String generateAccountNumber() {
        return "ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
