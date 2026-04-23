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
- Transfer flow now uses **pessimistic DB locking** (`PESSIMISTIC_WRITE`) on both accounts.
- We lock accounts in a **stable sorted order** to reduce deadlock risk.
- This prevents two parallel requests (or two app instances) from debiting the same wallet at the same time.
- Idempotency (`transactionReference`) uses a **reservation-first** approach (`PENDING` -> `SUCCESS`) to avoid same-key races across nodes.
- Same key + same payload returns the original result; same key + different payload is rejected.
- Database check constraint (`amount >= 0`) adds a hard safety net against negative balances.

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
