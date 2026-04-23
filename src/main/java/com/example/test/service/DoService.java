package com.example.test.service;

import com.example.test.dto.BalanceResponse;
import com.example.test.dto.CreateUserAccountRequest;
import com.example.test.dto.CreateUserAccountResponse;
import com.example.test.dto.DoTransDto;
import com.example.test.dto.TransferResponse;
import com.example.test.exception.AccountNotFoundException;
import com.example.test.exception.AccountNumberGenerationException;
import com.example.test.exception.CircuitBreakerOpenException;
import com.example.test.exception.DuplicateUserException;
import com.example.test.exception.InsufficientFundsException;
import com.example.test.exception.InvalidTransferException;
import com.example.test.exception.RateLimitExceededException;
import com.example.test.model.Account;
import com.example.test.model.TransactionHistory;
import com.example.test.model.User;
import com.example.test.model.WalletBalance;
import com.example.test.repo.AccountRepo;
import com.example.test.repo.UserRepo;
import com.example.test.repo.WalletBalanceRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockTimeoutException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DoService implements ServiceCall {

    private final UserRepo userRepo;
    private final AccountRepo accountRepo;
    private final WalletBalanceRepo walletBalanceRepo;
    private final TransferIdempotencyService transferIdempotencyService;
    private final AuditLogService auditLogService;
    private final Duration idempotencyReplayWindow;
    private final int transferMaxRequestsPerSecond;
    private final Semaphore transferBackpressureSemaphore;
    private final int transferCircuitFailureThreshold;
    private final Duration transferCircuitOpenDuration;
    private final AtomicLong rateWindowEpochSecond;
    private final AtomicInteger rateWindowCount;
    private final AtomicInteger consecutiveTransientFailures;
    private final AtomicLong circuitOpenUntilEpochMs;

    public DoService(
            UserRepo userRepo,
            AccountRepo accountRepo,
            WalletBalanceRepo walletBalanceRepo,
            TransferIdempotencyService transferIdempotencyService,
            AuditLogService auditLogService,
            @Value("${wallet.idempotency.replay-window-hours:24}") long replayWindowHours,
            @Value("${wallet.transfer.rate-limit-per-second:1000}") int transferRateLimitPerSecond,
            @Value("${wallet.transfer.max-inflight:200}") int transferMaxInflight,
            @Value("${wallet.transfer.circuit-breaker.failure-threshold:20}") int transferCircuitFailureThreshold,
            @Value("${wallet.transfer.circuit-breaker.open-seconds:15}") long transferCircuitOpenSeconds
    ) {
        this.userRepo = userRepo;
        this.accountRepo = accountRepo;
        this.walletBalanceRepo = walletBalanceRepo;
        this.transferIdempotencyService = transferIdempotencyService;
        this.auditLogService = auditLogService;
        this.idempotencyReplayWindow = Duration.ofHours(replayWindowHours);
        this.transferMaxRequestsPerSecond = transferRateLimitPerSecond;
        this.transferBackpressureSemaphore = new Semaphore(transferMaxInflight, true);
        this.transferCircuitFailureThreshold = transferCircuitFailureThreshold;
        this.transferCircuitOpenDuration = Duration.ofSeconds(transferCircuitOpenSeconds);
        this.rateWindowEpochSecond = new AtomicLong(Instant.now().getEpochSecond());
        this.rateWindowCount = new AtomicInteger(0);
        this.consecutiveTransientFailures = new AtomicInteger(0);
        this.circuitOpenUntilEpochMs = new AtomicLong(0L);
    }


    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Retryable(
            retryFor = DataIntegrityViolationException.class,
            maxAttemptsExpression = "${wallet.retry.create-user.max-attempts:4}",
            backoff = @Backoff(
                    delayExpression = "${wallet.retry.create-user.delay-ms:25}",
                    maxDelayExpression = "${wallet.retry.create-user.max-delay-ms:300}",
                    multiplier = 2.0,
                    random = true
            )
    )
    public CreateUserAccountResponse createUserAndAccount(CreateUserAccountRequest request) {
        if (userRepo.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateUserException("User already exists for email: " + request.getEmail());
        }

        BigDecimal initialBalance = request.getInitialBalance() == null ? BigDecimal.ZERO : normalizeMoneyAmount(request.getInitialBalance());
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse doIntraTransfer(DoTransDto request) {
        ensureTransferCircuitClosed();
        // Overload protection:
        // 1) rate limit smooths burst traffic.
        // 2) backpressure caps in-flight transfers to protect DB and thread pools.
        enforceTransferRateLimit();
        boolean permitAcquired = false;
        boolean idempotencyReserved = false;
        if (!transferBackpressureSemaphore.tryAcquire()) {
            registerTransientFailure();
            throw new RateLimitExceededException("Transfer service is busy. Please retry shortly.");
        }
        permitAcquired = true;
        try {
        // Distributed systems are usually at-least-once, not exactly-once.
        // Clients, gateways, or queues can redeliver the same transfer request.
        // We therefore enforce dedupe using transactionReference.
        // Every transfer must carry a client-generated idempotency key.
        // This lets us safely handle client retries without applying money movement twice.
        if (request.getTransactionReference() == null || request.getTransactionReference().isBlank()) {
            throw new InvalidTransferException("Transaction reference is required");
        }

        if (request.getFromAccount().equals(request.getToAccount())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }
        BigDecimal normalizedAmount = normalizeMoneyAmount(request.getAmount());
        if (normalizedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be greater than zero");
        }

        // Reserve idempotency in an isolated transaction to avoid rollback-only poisoning
        // on constraint conflicts in this main business transaction.
        TransactionHistory existingTransaction = null;
        try {
            transferIdempotencyService.reserveNew(
                    request.getTransactionReference(),
                    request.getFromAccount(),
                    request.getToAccount(),
                    normalizedAmount
            );
            idempotencyReserved = true;
            appendAuditLog(request.getTransactionReference(), "TRANSFER_ACCEPTED", "Transfer request accepted and reserved.");
        } catch (DataIntegrityViolationException ex) {
            // Read existing idempotency row in a fresh REQUIRES_NEW transaction.
            // This avoids using a persistence context that just failed on flush.
            existingTransaction = transferIdempotencyService.getByTransactionReference(request.getTransactionReference());
            validateIdempotencyPayload(existingTransaction, request.getFromAccount(), request.getToAccount(), normalizedAmount);
            // Enforce replay window so stale idempotency keys do not live forever.
            if (isReplayExpired(existingTransaction)) {
                appendAuditLog(request.getTransactionReference(), "TRANSFER_REPLAY_EXPIRED", "Replay window expired for transaction reference.");
                throw new InvalidTransferException("Transaction reference expired. Use a new transaction reference.");
            }
            if ("SUCCESS".equals(existingTransaction.getStatus())) {
                // Partial-failure safety:
                // If DB already committed SUCCESS but client never received response (timeout/network),
                // a retry with the same transactionReference must replay the same outcome, not re-debit.
                appendAuditLog(request.getTransactionReference(), "TRANSFER_REPLAYED", "Idempotent replay returned persisted success result.");
                TransferResponse replay = buildTransferResponseFromHistory(existingTransaction);
                resetTransientFailureState();
                return replay;
            }
            if ("FAILED".equals(existingTransaction.getStatus())) {
                throw new InvalidTransferException("Transaction reference already failed previously. Use a new transaction reference.");
            }
            appendAuditLog(request.getTransactionReference(), "TRANSFER_REPLAY_IN_PROGRESS", "Transaction reference is currently in progress.");
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

        if (fromBalance.getAmount().compareTo(normalizedAmount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account: " + request.getFromAccount());
        }

        // Because rows are pessimistically locked inside this transaction,
        // no concurrent transfer can change these two balances until commit/rollback.
        fromBalance.setAmount(fromBalance.getAmount().subtract(normalizedAmount));
        toBalance.setAmount(toBalance.getAmount().add(normalizedAmount));

        walletBalanceRepo.save(fromBalance);
        walletBalanceRepo.save(toBalance);

        TransactionHistory history = transferIdempotencyService.markSuccess(
                request.getTransactionReference(),
                fromBalance.getAmount(),
                toBalance.getAmount()
        );
        appendAuditLog(
                request.getTransactionReference(),
                "TRANSFER_SUCCESS",
                "Transfer committed successfully from " + request.getFromAccount() + " to " + request.getToAccount()
        );

        TransferResponse response = buildTransferResponseFromHistory(history);
        resetTransientFailureState();
        return response;
        } catch (RuntimeException ex) {
            if (idempotencyReserved && request.getTransactionReference() != null && !request.getTransactionReference().isBlank()) {
                transferIdempotencyService.markFailed(request.getTransactionReference(), ex.getClass().getSimpleName());
            }
            if (isTransientTransferFailure(ex)) {
                registerTransientFailure();
            }
            throw ex;
        } finally {
            if (permitAcquired) {
                transferBackpressureSemaphore.release();
            }
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BalanceResponse getBalance(String accountNumber) {
        Account account = accountRepo.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
        return new BalanceResponse(accountNumber, account.getWalletBalance().getAmount());
    }

    private String generateAccountNumber() {
        return "ACC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private TransferResponse buildTransferResponseFromHistory(TransactionHistory history) {
        // Replay response from persisted transfer snapshot.
        // This keeps retried responses stable even when account balances changed later due to other transfers.
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

    private void validateIdempotencyPayload(TransactionHistory savedHistory, String fromAccount, String toAccount, BigDecimal amount) {
        // Strong production rule:
        // A transaction reference is immutable and tied to one exact payload.
        // If caller reuses key with a different amount/accounts, fail fast.
        boolean sameFrom = savedHistory.getFromAccount().equals(fromAccount);
        boolean sameTo = savedHistory.getToAccount().equals(toAccount);
        boolean sameAmount = savedHistory.getAmount().compareTo(amount) == 0;

        if (!sameFrom || !sameTo || !sameAmount) {
            throw new InvalidTransferException(
                    "Transaction reference already used with different payload"
            );
        }
    }

    private boolean isReplayExpired(TransactionHistory savedHistory) {
        Instant createdAt = savedHistory.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        return createdAt.plus(idempotencyReplayWindow).isBefore(Instant.now());
    }

    private void enforceTransferRateLimit() {
        long nowSecond = Instant.now().getEpochSecond();
        long activeWindow = rateWindowEpochSecond.get();
        if (nowSecond != activeWindow) {
            if (rateWindowEpochSecond.compareAndSet(activeWindow, nowSecond)) {
                rateWindowCount.set(0);
            }
        }
        int count = rateWindowCount.incrementAndGet();
        if (count > transferMaxRequestsPerSecond) {
            throw new RateLimitExceededException("Transfer rate limit exceeded. Please retry shortly.");
        }
    }

    private void ensureTransferCircuitClosed() {
        if (Instant.now().toEpochMilli() < circuitOpenUntilEpochMs.get()) {
            throw new CircuitBreakerOpenException("Transfer circuit is temporarily open due to repeated transient failures. Please retry shortly.");
        }
    }

    private void registerTransientFailure() {
        int failures = consecutiveTransientFailures.incrementAndGet();
        if (failures >= transferCircuitFailureThreshold) {
            circuitOpenUntilEpochMs.set(Instant.now().plus(transferCircuitOpenDuration).toEpochMilli());
            consecutiveTransientFailures.set(0);
        }
    }

    private void resetTransientFailureState() {
        consecutiveTransientFailures.set(0);
        if (Instant.now().toEpochMilli() >= circuitOpenUntilEpochMs.get()) {
            circuitOpenUntilEpochMs.set(0L);
        }
    }

    private boolean isTransientTransferFailure(Throwable ex) {
        return ex instanceof RateLimitExceededException
                || ex instanceof CannotAcquireLockException
                || ex instanceof PessimisticLockingFailureException
                || ex instanceof DeadlockLoserDataAccessException
                || ex instanceof LockTimeoutException;
    }

    private void appendAuditLog(String transactionReference, String eventType, String details) {
        // Append-only immutable audit trail for forensic and compliance use.
        auditLogService.append(transactionReference, eventType, details);
    }

    private BigDecimal normalizeMoneyAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw new InvalidTransferException("Transfer amount must be provided");
        }
        try {
            return rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new InvalidTransferException("Amount must have at most 2 decimal places");
        }
    }
}
