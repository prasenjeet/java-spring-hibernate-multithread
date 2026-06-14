# Getting Started

## Prerequisites

| Tool | Minimum version |
|------|-----------------|
| JDK  | 21 |
| Maven | 3.8 |

## Clone and build

```bash
git clone https://github.com/prasenjeet/java-spring-hibernate-multithread.git
cd java-spring-hibernate-multithread
mvn compile
```

## Run all demos

```bash
mvn spring-boot:run
```

The application starts, runs all five demos sequentially, and prints structured
log output to the console.  You should see sections like:

```
═══════════════════════════════════════════════════════
DEMO 1 — @Async + @Transactional
═══════════════════════════════════════════════════════
[async-worker-2] Price update committed for product #1: 10.00 → 11.00
...
═══════════════════════════════════════════════════════
DEMO 2 — Optimistic Locking with Retry
═══════════════════════════════════════════════════════
...
```

## Run the test suite

```bash
mvn test
```

Expected output: **20 tests, 0 failures**.

## H2 web console

While the app is running, open <http://localhost:8080/h2-console> to inspect
tables live.

| Field | Value |
|-------|-------|
| JDBC URL | `jdbc:h2:mem:multithreaddb` |
| Username | `sa` |
| Password | *(empty)* |

## Key files to read first

| File | Why |
|------|-----|
| `config/AsyncConfig.java` | Thread-pool definitions (sizes, names, rejection policy) |
| `entity/Product.java` | Shows `@Version` for optimistic locking |
| `service/BankTransferService.java` | Shows canonical lock-ordering pattern |
| `service/BatchTransactionHelper.java` | Explains the Spring AOP self-invocation fix |
| `runner/DemoRunner.java` | Top-level entry point that sequences all demos |
