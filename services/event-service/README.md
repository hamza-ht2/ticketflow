# event-service

> Events catalog: the second business microservice of TicketFlow.

## Concept illustrated
A second REST service, built exactly like `customer-service`, that **registers itself
in Eureka** from day one. The point of this step: the service becomes reachable through
the gateway **without any change in the gateway** (discovery locator).

It also shows a small **life cycle** (state machine) enforced by the domain object:
`DRAFT -> PUBLISHED -> CLOSED`.

## Overview
- **Port:** 8082
- **Database:** PostgreSQL, database `event` (started by `infra/docker-compose.yml`). Data survives restarts.
  Tests use an in-memory H2 instead, so they need no Docker
- **Depends on:** `discovery-server` (8761), only to register itself; the API works without it

| Method | Path | Description |
|---|---|---|
| POST | `/api/events` | Create an event (201 + Location), always as a `DRAFT` |
| GET | `/api/events` | Public catalog: **PUBLISHED** events only, ordered by date. Optional `?venue=` filter |
| GET | `/api/events/{id}` | Get one event, whatever its status |
| PUT | `/api/events/{id}` | Update an event |
| POST | `/api/events/{id}/publish` | `DRAFT -> PUBLISHED` |
| POST | `/api/events/{id}/close` | `PUBLISHED -> CLOSED` |

Fields: `title`, `description`, `venue`, `startsAt` (ISO-8601, must be in the future),
`price` (>= 0), `capacity` (>= 1), `status`.

Business rules:
- A `CLOSED` event cannot be modified.
- Once `PUBLISHED`, the capacity can grow but never be lowered: seats may already be sold, and the
  booking service (a later step) owns that count.
- An invalid transition (publish twice, close a draft...) answers **409**.

Errors follow RFC 9457 (`ProblemDetail`): 400 validation, 404 not found, 409 invalid state.

> There is no security yet: `GET /api/events/{id}` also returns drafts. Access control
> arrives in `step-12-security`.

## Package layout
    controllers/   REST endpoints
    service/       business logic
    repos/         Spring Data repositories
    entities/      JPA entity + life cycle rules (Event, EventStatus)
    dto/           request/response records (the API contract)
    exceptions/    domain exceptions and their HTTP mapping

## Service discovery
The service registers itself in Eureka under the name `event-service`
(`spring.application.name`). Eureka address: `eureka.client.service-url.defaultZone`,
overridable with the `EUREKA_URL` environment variable (default `http://localhost:8761/eureka/`).

## Central configuration
The service imports shared configuration from the Config Server
(`spring.config.import=optional:configserver:...`, default `http://localhost:8888`, `CONFIG_SERVER_URL` in Compose).
It gets `config-repo/application.yml`; add `config-repo/event-service.yml` for settings specific to it.
The service still starts without the Config Server (`optional:`).

## Run locally
Start PostgreSQL first (it creates the `event` and `booking` databases the first time):

    docker compose -f infra/docker-compose.yml up -d postgres

Optional: start the registry (`./mvnw -pl services/discovery-server spring-boot:run`) too.

    ./mvnw -pl services/event-service spring-boot:run

Create, publish and list an event (replace the date by a future one):

    curl -i -X POST localhost:8082/api/events \
      -H 'Content-Type: application/json' \
      -d '{"title":"Gnawa Festival","venue":"Essaouira","startsAt":"2030-06-20T20:00:00Z","price":250.00,"capacity":500}'

    curl -i -X POST localhost:8082/api/events/1/publish
    curl localhost:8082/api/events

## Through the gateway (no gateway change needed)
With discovery-server, event-service and api-gateway running, wait about 30 s and call:

    curl localhost:8080/event-service/api/events

The gateway discovered `event-service` on its own and created the route `/event-service/**`.

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build event-service

## Run the tests
    ./mvnw -pl services/event-service test

Tests run with an in-memory H2 and `eureka.client.enabled=false`, so neither Docker nor a registry is needed.

## Persistence check
Create an event, stop the service (Ctrl+C), start it again and call `GET /api/events/1`: the event is still there,
because it lives in PostgreSQL (volume `postgres-data`). `docker compose -f infra/docker-compose.yml down -v`
deletes the volume, and the data with it.

## Exercise
1. Start the three services and open http://localhost:8761. Which names appear? Call
   `event-service` through the gateway. What did you have to change in the gateway?
2. Try to lower the capacity of a published event, then to modify a closed one. Which
   status do you get, and why 409 rather than 400?
3. Add a `GET /api/events?from=...&to=...` filter on the date, with a test.

## Common pitfalls
- A new event is a `DRAFT` and does **not** appear in the catalog until it is published.
- `startsAt` must be a future ISO-8601 instant (`2030-06-20T20:00:00Z`); a past date answers 400.
- Business rules live in the `Event` entity (`publish()`, `close()`, `update(...)`), not in the
  controller: they are unit-tested without Spring or a database.
- An in-memory database is fine for learning but never shared between services:
  each service owns its data.
