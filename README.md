# Payment Gateway

The application exposes a REST API for processing card payments through an external bank simulator. It validates requests, authorizes payments, stores successful and declined payment results, and supports idempotent payment processing to safely handle client retries.

## Requirements
- JDK 17
- Docker
- Gradle

## Running the application

Start the Bank Simulator:

```bash
docker compose up
```

Run the application:

```bash
./gradlew bootRun
```

## API Documentation

Swagger UI is available at:

```
http://localhost:8090/swagger-ui/index.html
```

---

## Features

- Process card payments through an external bank simulator
- Retrieve previously processed payments
- Comprehensive request validation
- Idempotency support using the `Idempotency-Key` header
- Thread-safe handling of concurrent duplicate requests
- Secure handling of sensitive card information
- Micrometer metrics for observability
- Unit, integration and end-to-end tests

---

## API Endpoints

### Process a payment

```
POST /payments
```

Headers

```
Idempotency-Key: unique-request-id
```

Example request

```json
{
  "card_number": "2222405343248877",
  "expiry_month": 12,
  "expiry_year": 2027,
  "currency": "GBP",
  "amount": 1050,
  "cvv": "123"
}
```
Returns:

- 201 Created
- 400 Bad Request
- 409 Conflict
- 502 Bad Gateway
- 503 Service Unavailable

---

### Retrieve a payment

```
GET /payments/{paymentId}
```

Returns:

- 200 OK
- 404 Not Found

---

## Solution Overview

The application follows a simple layered architecture:

```
POST /payments
                Client
                   │
                   ▼
        PaymentGatewayController
                   │
                   ▼
         PaymentGatewayService
        ├─────────────────────────────┐
        │                             │
        ▼                             ▼
 PaymentValidator         PaymentRequestFingerprint
        │                             │
        └──────────────┬──────────────┘
                       ▼
            IdempotencyRepository
                       │
                       ▼
             BankPaymentMapper
                       │
                       ▼
            BankSimulatorClient
                       │
                       ▼
               Bank Simulator

                       │
                       ▼
             PaymentsRepository
```

The application follows a layered architecture where the controller is responsible for the HTTP contract, the service orchestrates the payment workflow, validators enforce business rules, the mapper isolates the external bank contract, and repositories manage in-memory state.

Payments are stored in-memory using a `ConcurrentHashMap`.

---

## Design Decisions

### In-memory persistence

The assignment does not require persistent storage, therefore payments and idempotency records are stored using thread-safe `ConcurrentHashMap` implementations.

### Layered architecture

Business logic is isolated from the REST API and the external bank contract to improve maintainability and testability.

### Idempotency

Idempotent requests are implemented using atomic reservation and `CompletableFuture` to ensure correct behaviour under concurrent retries without blocking unrelated requests. As POST method on its own it is not idempotent.

---

## Validation

Incoming requests are validated before contacting the Bank Simulator.

Validation includes:

- Card number
- Expiry date
- Currency
- Amount
- CVV

Validation failures return HTTP 400.

---

## Testing

The project includes multiple levels of automated testing.

### Unit Tests

- validation
- mapping
- request fingerprinting
- repositories
- service layer
- exception handling

### Integration Tests

MockMvc tests verify the REST API behaviour and HTTP responses.

### End-to-End Tests

End-to-end tests execute the complete payment flow against the real Bank Simulator running in Docker.

The test suite covers:

- successful authorization
- declined payments
- validation failures
- bank failures
- idempotent replay
- idempotency conflicts
- concurrent duplicate requests
- payment retrieval

---

## Idempotency

`POST /payments` supports idempotent requests using the `Idempotency-Key` header.

Each request is fingerprinted using the payment details and associated with the supplied idempotency key.

Behaviour:

| Request | Behaviour |
|---------|-----------|
| New key | Payment is processed |
| Same key + same request | Previously stored response is returned |
| Same key + different request | HTTP 409 Conflict |

To safely support concurrent retries, the implementation reserves each idempotency key atomically using `ConcurrentHashMap.putIfAbsent()` and stores the in-flight result in a `CompletableFuture`. This guarantees that only one request performs bank authorization while concurrent duplicates receive the same response.

---

## Security

Sensitive card data is handled carefully throughout the application.

- CVV is never stored.
- Only the last four digits of the card number are persisted.
- Full card numbers are never returned by the API.
- Sensitive information is excluded from application logs. 
- The idempotency fingerprint includes all payment fields, including CVV, to ensure semantically different requests cannot be replayed using the same key.

---

## Observability

Spring Boot Actuator and Micrometer are used to expose application metrics for operational visbility.

### Metrics

**payments.processed**

Tags:

- AUTHORIZED
- DECLINED

**payments.failed**

Tags:

- VALIDATION_ERROR
- BANK_REJECTED_REQUEST
- BANK_UNAVAILABLE
- UNEXPECTED_ERROR

**payments.processing.time**

Measures end-to-end payment processing latency.

**payments.idempotency**

Tags:

- REPLAYED
- CONFLICT
- IN_PROGRESS

Metrics are available under:

```
/actuator/metrics
```
```
http://localhost:8090/actuator/metrics/payments.processing.time
```
Using tags:
```
http://localhost:8090/actuator/metrics/payments.processed?tag=status:DECLINED
```
---

## Logging

The application logs important operational events including:

- Payment processing
- Payment retrieval
- Bank communication
- Validation failures
- Unexpected errors

Sensitive card information (PAN and CVV) is never logged.

---

## Assumptions

- Payments are stored in-memory for the purpose of this exercise.
- Idempotency is implemented in-memory and therefore scoped to a single application instance.
- The Bank Simulator is treated as the source of truth for payment authorization.
- Authentication and authorization are outside the scope of this challenge.

---

## Future Improvements

For a production-ready implementation, I would consider:

- Persistent storage
- Distributed idempotency (database or Redis)
- Idempotency key expiration
- Implementing tracing
- Authentication and authorization
- Retry resilience patterns
- Prometheus and Grafana integration

---

## AI Usage

Github Copilot and ChatGPT was used as a development assistant to review implementation ideas, discuss architectural trade-offs, improve documentation and identify potential edge cases.

All design decisions, implementation, debugging, testing, and final code changes were reviewed and completed by the author.
