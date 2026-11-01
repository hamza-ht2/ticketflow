# booking-service

> Seat reservations: the first service that **calls another service**.

## Concept illustrated
- **OpenFeign**: `EventClient` is a plain Java interface; Spring generates the HTTP code. It addresses
  `event-service` **by its Eureka name**, load-balanced, so the address of the other service is never written down.
- **Synchronous call, on purpose**: to book, we need the answer *now* (does the event exist? is it published?
  what is its price and capacity?). A question that needs an immediate answer is a REST call; a fact that can be
  processed later is an event (Kafka, a later step).
- **Our own view of the contract**: `EventView` keeps only the 6 fields booking needs and ignores the rest, so
  `event-service` can add fields without breaking this service.
- **Bounded waiting**: connect 2 s, read 3 s. Without timeouts, one slow service freezes this one too.
  `EventClientContractTest` proves it with a real slow server.
- **No overselling**: seats are counted in `seat_inventory`; reserving is **one atomic
  `UPDATE ... WHERE held + :q <= :capacity`**. Counting bookings and then inserting would be a race (two requests
  see one free seat). `BookingApiTest` fires 8 concurrent requests at 3 seats and expects exactly 3 successes.
- **Remote call before the transaction**: a slow service must not hold a database connection.

## Overview
- **Port:** 8083
- **Database:** PostgreSQL, database `booking` (started by `infra/docker-compose.yml`). Data survives restarts.
  `BookingApiTest` runs against a real PostgreSQL started by Testcontainers (Docker required)
- **Depends on:** `event-service` (through Eureka)

| Method | Path | Description |
|---|---|---|
| POST | `/api/bookings` | Book `quantity` (1-10) seats of a PUBLISHED, not yet started event. 201 + Location, status `PENDING` |
| GET | `/api/bookings` | All bookings, newest first. Optional `?customerId=` filter |
| GET | `/api/bookings/{id}` | One booking |
| POST | `/api/bookings/{id}/cancel` | Cancel and give the seats back (409 if already cancelled) |

Request: `{ "eventId": 5, "customerId": 42, "quantity": 2 }`. The unit price and the event title are **copied**
into the booking: a later price change does not alter what the customer agreed to.

> **Known limits of this step.** There is no security yet: `customerId` is taken from the request and is not
> checked against `customer-service`, and anybody can read or cancel any booking. The identity will come from the
> token in the security step. A booking stays `PENDING` until cancelled: `CONFIRMED` comes with the payment.

Errors (RFC 9457): 400 invalid quantity / customer / missing event, 404 unknown event or booking,
409 sold out / event not open / event already started / already cancelled, 503 `event-service` did not answer.

## Calling event-service
`EVENT_SERVICE_URL` overrides the Eureka name: `EVENT_SERVICE_URL=http://localhost:8082` runs booking with only
`event-service` started (no Eureka needed). Leave it empty to use the name.
Retries, circuit breaker and fallbacks are the subject of the resilience step: today a failure is just a clean 503.

## Run
```bash
# 0. PostgreSQL (creates the event and booking databases the first time)
docker compose -f infra/docker-compose.yml up -d postgres

# 1. an event, published (needs event-service on 8082)
curl -X POST localhost:8082/api/events -H 'Content-Type: application/json' \
  -d '{"title":"Jazz night","description":"Live","venue":"Casablanca","startsAt":"2030-01-01T20:00:00Z","price":150,"capacity":3}'
curl -X POST localhost:8082/api/events/1/publish

# 2. booking-service, pointing to event-service directly (no Eureka)
EVENT_SERVICE_URL=http://localhost:8082 ./mvnw -pl services/booking-service spring-boot:run

# 3. book
curl -X POST localhost:8083/api/bookings -H 'Content-Type: application/json' \
  -d '{"eventId":1,"customerId":42,"quantity":2}'
```
With Docker Compose (Eureka + Config Server), leave `EVENT_SERVICE_URL` empty: the name `event-service` is resolved
by Eureka. Through the gateway (discovery locator): `http://localhost:8080/booking-service/api/bookings`.

## Manual test scenario
Try the service with real HTTP requests, without Eureka or the Config Server (the "connection refused" warnings
about ports 8761 and 8888 can be ignored).

**1. Start the two services** (IntelliJ or `./mvnw -pl services/<name> spring-boot:run`)
- `event-service` on port 8082, no setting needed.
- `booking-service` on port 8083 with the environment variable `EVENT_SERVICE_URL=http://localhost:8082`.
  Without it, the service looks for `event-service` in Eureka.

**2. Create and publish an event with 3 seats** (use the id returned by the creation if it is not 1)
```bash
curl -s -X POST localhost:8082/api/events -H 'Content-Type: application/json' \
  -d '{"title":"Jazz night","description":"Live","venue":"Casablanca","startsAt":"2030-01-01T20:00:00Z","price":150,"capacity":3}'
curl -s -X POST localhost:8082/api/events/1/publish
```

**3. Booking and seat counting**

| Request | Expected result |
|---|---|
| `POST /api/bookings` `{"eventId":1,"customerId":42,"quantity":2}` | **201**, `status: PENDING`, `totalPrice: 300`, `Location` header |
| the same request again (only 1 seat left) | **409** "Not enough seats left" |
| `POST /api/bookings` `{"eventId":1,"customerId":43,"quantity":1}` | **201** (last seat) |
| the same request again | **409** (sold out) |
| `POST /api/bookings/1/cancel` | **200**, `status: CANCELLED`, 2 seats given back |
| `POST /api/bookings/1/cancel` again | **409** "already cancelled" (the seats are not released twice) |
| `POST /api/bookings` `{"eventId":1,"customerId":44,"quantity":2}` | **201** (the released seats can be booked again) |
| `GET /api/bookings/1` | **200** |
| `GET /api/bookings?customerId=42` | **200**, only the bookings of customer 42 |
| `GET /api/bookings/999` | **404** |

```bash
curl -i -X POST localhost:8083/api/bookings -H 'Content-Type: application/json' \
  -d '{"eventId":1,"customerId":42,"quantity":2}'
curl -i -X POST localhost:8083/api/bookings/1/cancel
curl -i 'localhost:8083/api/bookings?customerId=42'
```

**4. Errors that come from the call to event-service**

| Situation | Expected result |
|---|---|
| unknown event: `{"eventId":999,"customerId":42,"quantity":1}` | **404** |
| event still in `DRAFT` (created, not published) | **409** "not open for booking" |
| invalid request: `{"eventId":1,"quantity":0}` (no customer, quantity < 1) | **400** |

**5. event-service stopped**

Stop `event-service`, then book:
```bash
curl -i -X POST localhost:8083/api/bookings -H 'Content-Type: application/json' \
  -d '{"eventId":1,"customerId":42,"quantity":1}'
```
Expected: **503** "The event service is temporarily unavailable, please retry". The connection is refused, so the
answer is almost immediate; a service that is up but too slow is cut off after the 3 s read timeout
(`EventClientContractTest` checks this). Restart `event-service` and booking works again. The events live in
`event-service`'s PostgreSQL database, so they are still there after the restart.

**6. Through Eureka and the gateway (optional)**

Start `discovery-server` (8761) and `api-gateway` (8080), remove `EVENT_SERVICE_URL`, wait about 30 s for the
registration, then call `localhost:8080/booking-service/api/bookings`. This proves that the name `event-service` is
resolved by Eureka, which the automated tests do not cover.

## Tests
```bash
./mvnw -pl services/booking-service test
```
- `BookingApiTest`: booking rules, seat counting, concurrency (event-service mocked).
- `EventClientContractTest`: a fake `event-service` on a random port and the **real** Feign client: contract, 404,
  read timeout, and the end-to-end booking.
- `BookingTest`: the booking domain object.

## Exercises
1. Stop `event-service` and book: which status do you get, and after how long? Why does the timeout matter?
2. Make `EventView` stricter (remove `@JsonIgnoreProperties`) and add a field to `EventResponse`: what breaks, and where?
3. Replace the atomic `UPDATE` by "count, then insert" and run `concurrentBookingsOfTheLastSeatsCannotOversell`.
4. Add `?status=PENDING` to `GET /api/bookings`.
