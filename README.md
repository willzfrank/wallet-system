# Wallet System (Spring Boot)

Simple wallet service that demonstrates:
- User creation with auto-generated account
- Fund transfer between accounts
- Transactional updates with optimistic locking
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
- `WalletBalance` includes `@Version` for optimistic locking.
- `TransactionHistory` captures transfer audit trail.

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
  "fromAccount": "ACC-AAAA1111",
  "toAccount": "ACC-BBBB2222",
  "amount": 25.00
}
```

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
  -d '{"fromAccount":"ACC-XXXX1111","toAccount":"ACC-YYYY2222","amount":20.00}'
```

## Error Handling
Centralized by `GlobalExceptionHandler` (`@RestControllerAdvice`):
- `404` account not found
- `400` invalid transfer / insufficient funds / validation issues
- `409` duplicate user or optimistic lock conflict

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
- Add idempotency keys for transfer requests.
- Add authentication/authorization.
- Add pagination/filtering endpoint for transaction history.
