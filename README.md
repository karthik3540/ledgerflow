# LedgerFlow

A robust, production-grade financial ledger engine built with **Spring Boot 3**, **PostgreSQL**, **Redis**, and **RabbitMQ**.

## Core Features & Architecture

1. **Double-Entry Bookkeeping & Pessimistic Locking**:
   - Every financial transfer produces matched `DEBIT` and `CREDIT` rows in `ledger_entries`.
   - Guaranteed deadlock prevention via global ascending UUID row-locking ordering (`SELECT ... FOR UPDATE`).
   - Strong consistency tested under concurrent multi-threaded execution.

2. **Strict Idempotency Enforcement**:
   - `idempotency_keys` table tracking `PROCESSING` and `COMPLETED` request fingerprints.
   - Prevents double-spending and safely replays cached responses on network retries.

3. **Redis Cache-Aside Architecture**:
   - 30-second TTL on account balance lookups.
   - Race-safe post-commit cache eviction ensuring fresh reads immediately following transfers.

4. **Token-Bucket Rate Limiting**:
   - 10 requests/second per client via `X-Client-Id` header (with remote IP fallback).
   - Atomic evaluation executed in Redis using a custom Lua script.

5. **Asynchronous Audit Logging via RabbitMQ**:
   - Upon transfer completion, publishes `TransactionCompletedEvent` to `ledger.exchange`.
   - Decoupled `@RabbitListener` consumer on `ledger.queue` ingests events and persists entries to the `audit_log` table.
   - Fail-safe event publishing ensures messaging broker outages never compromise completed database transactions.

6. **k6 Concurrency & Load Testing**:
   - `load-tests/read-load.js`: Benchmarks `GET /accounts/{id}` throughput under 50 concurrent VUs.
   - `load-tests/write-load.js`: Stresses `POST /transfers` with distinct client IDs and unique idempotency keys under 50 concurrent VUs.

## Quick Start

### 1. Start Infrastructure
```bash
cd ledgerflow
docker compose up -d
```

### 2. Run Integration Tests
```bash
./mvnw test -Dspring.profiles.active=integration
```

### 3. Run Load Tests
```bash
k6 run load-tests/read-load.js
k6 run load-tests/write-load.js
```
