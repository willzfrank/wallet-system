# Wallet System (Spring Boot)

Simple wallet service that demonstrates:
- User creation with auto-generated account
- Fund transfer between accounts
- Transaction-safe fund movement with database row locking
- Global error handling and integration tests

## Tech Stack
- Java 17+
- Spring Boot 3
- Spring Web, Spring Data JPA, Validation
- H2 in-memory database
- JUnit 5 + MockMvc

## Assumptions
- One API call creates one user and one wallet account.
- `email` is unique per user.
- Transfers must be greater than `0`.
- Transfers between the same account are not allowed.
- Account number format is generated as `ACC-XXXXXXXX`.
- All transfers are recorded in `TransactionHistory`.

## Data Model
- `User` -> `Account` (`OneToMany`)
- `Account` -> `WalletBalance` (`OneToOne`)
- `WalletBalance` includes `@Version` as extra conflict safety.
- `TransactionHistory` captures transfer audit trail.

## Concurrency Safety (Important)
- Transfer flow uses **pessimistic DB locking** (`PESSIMISTIC_WRITE`) on both accounts.
- Account rows are locked in a **stable sorted order** to reduce deadlock risk.
- Duplicate-request protection is implemented with `transactionReference` idempotency.
- Reservation-first idempotency state machine is used (`PENDING` -> `SUCCESS`).
- Idempotency reservation/write uses isolated `REQUIRES_NEW` transactions to avoid rollback-only side effects in the transfer transaction.
- Same key + same payload returns the original persisted result.
- Same key + different payload is rejected to preserve request immutability.
- Database check constraint (`amount >= 0`) protects against negative balances.
- Transaction methods run with `READ_COMMITTED` isolation.

## Time/Clock Safety (Important)
- Transaction `createdAt` is generated from the **database clock** (`CURRENT_TIMESTAMP`).
- Application-side `LocalDateTime.now()` is not used for critical transfer ordering timestamps.
- This keeps a single trusted time source across multiple app instances.

## Partial Failure Safety (Important)
- Retries after timeout/network failure are handled by idempotent replay.
- Persisted `SUCCESS` snapshots (`fromBalanceAfter` / `toBalanceAfter`) are returned on valid retries.
- Duplicate debits are prevented when a client resubmits the same transfer request.
- Failed transfer attempts are persisted as `FAILED` idempotency state for deterministic retry behavior.

## Idempotency Lifecycle Policy
- Replay window is configurable with `wallet.idempotency.replay-window-hours` (default: `24`).
- Reusing a key after the replay window is rejected; clients must send a new `transactionReference`.
- Idempotency records are retained for a configurable period using `wallet.idempotency.retention-days` (default: `7`).
- Automatic cleanup job runs on `wallet.idempotency.cleanup-cron` (default: `0 0 2 * * *`) and deletes old `SUCCESS` records.

## Delivery Semantics
- Transfer processing is designed for **at-least-once delivery** (not exactly-once assumptions).
- Redelivered requests are deduplicated by unique `transactionReference`.
- First valid delivery performs the transfer; subsequent identical deliveries replay the persisted `SUCCESS` result.
- Payload mismatch for an existing key is rejected to prevent accidental key reuse.

## Rate Limiting and Backpressure
- Transfer endpoint enforces a configurable per-second rate limit via `wallet.transfer.rate-limit-per-second`.
- In-flight transfer concurrency is capped via `wallet.transfer.max-inflight` to protect DB and thread resources.
- Excess traffic is rejected quickly with HTTP `429` instead of allowing cascading slowdowns/timeouts.
- These controls work with idempotency and locking to keep the service stable during traffic spikes.

## Timeout, Retry, and Circuit Breaker Policy
- DB lock wait is bounded with `spring.jpa.properties.jakarta.persistence.lock.timeout=3000` to avoid indefinite waits.
- Create-user retry policy is bounded and jittered:
  - `wallet.retry.create-user.max-attempts`
  - `wallet.retry.create-user.delay-ms`
  - `wallet.retry.create-user.max-delay-ms`
- Transfer path includes a lightweight circuit breaker:
  - opens after `wallet.transfer.circuit-breaker.failure-threshold` transient failures
  - stays open for `wallet.transfer.circuit-breaker.open-seconds`
  - rejects fast with HTTP `503` while open

## Observability and Auditability
- Transfer events are written to an append-only `transfer_audit_logs` table.
- Audit records are immutable (`@PreUpdate` / `@PreRemove` guard blocks modifications and deletes).
- Key events are captured (accepted, replayed, expired replay, in-progress replay, success).
- Audit timestamps are DB-generated to keep consistent ordering across instances.
- Audit writes run in isolated `REQUIRES_NEW` transactions so failure traces are retained even when business transaction rolls back.

## Money and Identifier Safety
- Transfer/create amounts are normalized to 2 decimal places (`BigDecimal.setScale(2, UNNECESSARY)`), rejecting invalid precision.
- Account number generation uses a longer UUID-derived token (`ACC-` + 16 hex chars) to reduce collision risk.

## API Endpoints

### 1) Create User + Account
`POST /api/wallet/users`

Request:
```json
{
  "email": "alice@example.com",
  "initialBalance": 100.00
}
```

### 2) Transfer Funds
`POST /api/wallet/transfer`

Request:
```json
{
  "transactionReference": "TXN-20260414-0001",
  "fromAccount": "ACC-AAAA1111",
  "toAccount": "ACC-BBBB2222",
  "amount": 25.00
}
```

`transactionReference` is required and used as an idempotency key. Repeating the same reference returns the original transfer result instead of re-debiting.
If the same `transactionReference` is reused with a different payload (different source/destination/amount), the request is rejected.

### 3) Get Account Balance
`GET /api/wallet/accounts/{accountNumber}/balance`

## API Docs (Swagger UI)
- Swagger UI: `http://localhost:9090/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:9090/v3/api-docs`

## Setup & Run
From the repository root (your current folder):

```bash
./mvnw clean test
./mvnw spring-boot:run
```

Server runs on `http://localhost:9090`.

If needed, confirm you are at the root before running commands:
```bash
pwd
ls
```

## Quick Curl Demo
```bash
curl -X POST http://localhost:9090/api/wallet/users \
  -H "Content-Type: application/json" \
  -d '{"email":"a@example.com","initialBalance":120.00}'
```

```bash
curl -X POST http://localhost:9090/api/wallet/users \
  -H "Content-Type: application/json" \
  -d '{"email":"b@example.com","initialBalance":30.00}'
```

```bash
curl -X POST http://localhost:9090/api/wallet/transfer \
  -H "Content-Type: application/json" \
  -d '{"transactionReference":"TXN-DEMO-1","fromAccount":"ACC-XXXX1111","toAccount":"ACC-YYYY2222","amount":20.00}'
```

## Error Handling
Centralized by `GlobalExceptionHandler` (`@RestControllerAdvice`):
- `404` account not found
- `400` invalid transfer / insufficient funds / validation issues
- `409` duplicate user / account-number generation conflicts
- `409` lock contention/deadlock/timeout during concurrent transfers (retryable)
- `429` rate-limit/backpressure rejection during overload
- `503` transfer circuit-breaker open during repeated transient failure bursts

## Testing
Integration tests cover:
- successful create + transfer flow
- insufficient funds
- invalid amount
- account-not-found behavior

Run:
```bash
./mvnw test
```

## Future Improvements
- Add authentication/authorization.
- Add pagination/filtering endpoint for transaction history.
