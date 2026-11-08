# payment-service

> A **simulated** payment provider: charge and refund a booking, with outcomes you can trigger on demand.

## Concept illustrated
- **A port and an adapter**: the business code only knows the `PaymentProvider` interface.
  `SimulatedPaymentProvider` is the only implementation today; a real provider (PayPal sandbox, Stripe...) would
  be a second one, with no change in `PaymentService`.
- **Deterministic simulation**: the outcome depends only on the `cardToken` sent in the request, so every case
  (success, refusal, timeout, outage) can be produced in a test or a demo.
- **Idempotent charge**: **one payment per booking**, enforced by a unique column. Sending the same request twice
  returns the existing payment (`200`) and charges nothing more. A different amount for the same booking is a `409`.
- **Claim, call, record**: the payment is first inserted as `PENDING` (this *claims* the booking, even against a
  concurrent request), then the provider is called, then the answer is recorded. The provider call is never inside
  a database transaction.
- **A refusal is a result, not an error**: a declined card answers `201` with `"status": "FAILED"`; only an
  unreachable provider is an HTTP error (`503`).
- **Idempotent refund**: refunding a refunded payment returns it unchanged. This is the shape a saga compensation
  must have, because it can be replayed.

## Overview
- **Port:** 8084
- **Database:** PostgreSQL, database `payment` (created by `infra/postgres/init/01-create-databases.sql`;
  on an existing volume: `CREATE DATABASE payment;` by hand). Tests use H2.
- **Depends on:** nothing. It is called by `booking-service` in a later step.

| Method | Path | Description |
|---|---|---|
| POST | `/api/payments` | Charge a booking. `201` + Location if created, `200` if the booking already had a payment |
| GET | `/api/payments` | All payments, newest first. Optional `?bookingId=` filter |
| GET | `/api/payments/{id}` | One payment |
| POST | `/api/payments/{id}/refund` | Refund a `SUCCEEDED` payment (`409` otherwise; refunding twice is harmless) |

Request: `{ "bookingId": 12, "amount": 150.00, "cardToken": "tok_success" }` (`cardToken` is optional).

### Statuses
`PENDING` (provider being called) → `SUCCEEDED` or `FAILED`; `SUCCEEDED` → `REFUNDED`.
A `FAILED` payment can be tried again on the **same** row (`FAILED` → `PENDING`).

### Test cards (`cardToken`)
| Token | Result |
|---|---|
| none or `tok_success` | `SUCCEEDED` with a provider reference |
| `tok_declined` | `FAILED`, reason `card_declined` |
| `tok_insufficient_funds` | `FAILED`, reason `insufficient_funds` |
| `tok_slow` | `SUCCEEDED` after `ticketflow.payment.simulated.slow-delay` (5 s by default): to see timeouts |
| `tok_error` | `503`, payment left `FAILED` (`provider_unavailable`) so it can be retried |
| anything else | `FAILED`, reason `unknown_card` |

## Configuration
The datasource comes from the Config Server, like the other services. Create `payment-service.yml` in the
configuration repository:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/payment}
    username: ${DB_USER:ticketflow}
    password: ${DB_PASSWORD:ticketflow}
  jpa:
    hibernate:
      ddl-auto: update
```

## Run locally
Start Postgres, Config Server, Discovery, then:

    ./mvnw -pl services/payment-service spring-boot:run

    curl -i -X POST localhost:8084/api/payments -H 'Content-Type: application/json' \
      -d '{"bookingId":1,"amount":150.00}'

## Tests
`./mvnw -pl services/payment-service test`: `PaymentApiTest` covers success, refusal, replay without a second
charge, amount conflict, retry after failure, provider outage, refund (twice), validation and 404.

> **Known limits of this step.** Nothing calls this service yet: booking stays `PENDING` until the next step. There
> is no security (anybody can charge or refund), and the booking id is not checked against `booking-service`.
> A payment stuck in `PENDING` (service killed during the provider call) is never released: an expiry comes later.
> With a real provider, two simultaneous refunds would both reach it: it would need its own idempotency key.

## Exercises
1. Charge booking 1 with `tok_declined`, then again with `tok_success`: same `id`, new status. Why does the row stay the same?
2. Send the same request ten times in a row: how many payments exist (`GET /api/payments?bookingId=1`)?
3. Send two identical requests **at the same time** (two terminals): what happens to the second, and why?
4. Add a second `PaymentProvider` (a stub that always refuses) and select it with a property.
5. Charge with `tok_slow` while a client has a 3 s read timeout: what does the client see, and what is the payment's final state?
