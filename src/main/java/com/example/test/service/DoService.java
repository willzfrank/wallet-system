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
    public TransferResponse doIntraTransfer(DoTransDto request) {
        // Every transfer must carry a client-generated idempotency key.
        // This lets us safely handle client retries without applying money movement twice.
        if (request.getTransactionReference() == null || request.getTransactionReference().isBlank()) {
            throw new InvalidTransferException("Transaction reference is required");
        }

        if (request.getFromAccount().equals(request.getToAccount())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be greater than zero");
        }

        // Reservation-first idempotency:
        // We insert a PENDING row first so only one request can own this transactionReference.
        // This closes the race where two nodes could both pass a read-check before debit/credit.
        TransactionHistory history = new TransactionHistory();
        history.setFromAccount(request.getFromAccount());
        history.setToAccount(request.getToAccount());
        history.setAmount(request.getAmount());
        history.setCreatedAt(LocalDateTime.now());
        history.setTransactionReference(request.getTransactionReference());
        history.setStatus("PENDING");
        try {
            transactionHistoryRepo.saveAndFlush(history);
        } catch (DataIntegrityViolationException ex) {
            TransactionHistory existingTransaction = transactionHistoryRepo
                    .findByTransactionReference(request.getTransactionReference())
                    .orElseThrow(() -> ex);
            validateIdempotencyPayload(existingTransaction, request);
            if ("SUCCESS".equals(existingTransaction.getStatus())) {
                return buildTransferResponseFromHistory(existingTransaction);
            }
            throw new InvalidTransferException("Transaction reference is currently being processed. Please retry.");
        }

        // Lock both account rows in deterministic order.
        // Why: if two transfers run in opposite directions at same time, consistent ordering avoids deadlocks.
        String firstLockAccountNumber = request.getFromAccount().compareTo(request.getToAccount()) < 0
                ? request.getFromAccount()
                : request.getToAccount();
        String secondLockAccountNumber = request.getFromAccount().compareTo(request.getToAccount()) < 0
                ? request.getToAccount()
                : request.getFromAccount();

        Account firstLockedAccount = accountRepo.findByAccountNumberForUpdate(firstLockAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstLockAccountNumber));
        Account secondLockedAccount = accountRepo.findByAccountNumberForUpdate(secondLockAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondLockAccountNumber));

        // Map locked rows back to business roles (source/destination).
        Account fromAccount = firstLockedAccount.getAccountNumber().equals(request.getFromAccount())
                ? firstLockedAccount
                : secondLockedAccount;
        Account toAccount = firstLockedAccount.getAccountNumber().equals(request.getToAccount())
                ? firstLockedAccount
                : secondLockedAccount;

        WalletBalance fromBalance = fromAccount.getWalletBalance();
        WalletBalance toBalance = toAccount.getWalletBalance();

        if (fromBalance.getAmount().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account: " + request.getFromAccount());
        }

        // Because rows are pessimistically locked inside this transaction,
        // no concurrent transfer can change these two balances until commit/rollback.
        fromBalance.setAmount(fromBalance.getAmount().subtract(request.getAmount()));
        toBalance.setAmount(toBalance.getAmount().add(request.getAmount()));

        walletBalanceRepo.save(fromBalance);
        walletBalanceRepo.save(toBalance);

        history.setStatus("SUCCESS");
        history.setFromBalanceAfter(fromBalance.getAmount());
        history.setToBalanceAfter(toBalance.getAmount());
        transactionHistoryRepo.save(history);

        return buildTransferResponseFromHistory(history);
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

    private TransferResponse buildTransferResponseFromHistory(TransactionHistory history) {
        // Prefer persisted post-transfer balances for exact idempotent replay response.
        BigDecimal fromBalanceAfter = history.getFromBalanceAfter();
        BigDecimal toBalanceAfter = history.getToBalanceAfter();
        if (fromBalanceAfter == null || toBalanceAfter == null) {
            BalanceResponse fromBalance = getBalance(history.getFromAccount());
            BalanceResponse toBalance = getBalance(history.getToAccount());
            fromBalanceAfter = fromBalance.getBalance();
            toBalanceAfter = toBalance.getBalance();
        }
        return new TransferResponse(
                history.getFromAccount(),
                history.getToAccount(),
                history.getAmount(),
                fromBalanceAfter,
                toBalanceAfter
        );
    }

    private void validateIdempotencyPayload(TransactionHistory savedHistory, DoTransDto request) {
        // Strong production rule:
        // A transaction reference is immutable and tied to one exact payload.
        // If caller reuses key with a different amount/accounts, fail fast.
        boolean sameFrom = savedHistory.getFromAccount().equals(request.getFromAccount());
        boolean sameTo = savedHistory.getToAccount().equals(request.getToAccount());
        boolean sameAmount = savedHistory.getAmount().compareTo(request.getAmount()) == 0;

        if (!sameFrom || !sameTo || !sameAmount) {
            throw new InvalidTransferException(
                    "Transaction reference already used with different payload"
            );
        }
    }
}
