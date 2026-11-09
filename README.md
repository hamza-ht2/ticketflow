# TicketFlow

**A hands-on reference project for learning Spring Cloud and microservices, built around a real-world ticket booking platform.**

TicketFlow is an open-source educational project that takes you from
"what is a microservice?" to building resilient, event-driven, observable
distributed systems with the Spring ecosystem.

Instead of yet another e-commerce demo, it tackles problems that naturally
require distributed-systems patterns: limited seat inventory, concurrent
bookings, unreliable payment providers, and asynchronous notifications.

Le cahier des charges détaillé (en français) est disponible dans
[`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md). Il distingue les
composants déjà présents des éléments planifiés et des choix restant à valider.

## What you will learn

- **Service discovery & routing**: Eureka and Spring Cloud Gateway
- **Centralized configuration**: Spring Cloud Config with dynamic refresh
- **Inter-service communication**: declarative REST clients with OpenFeign
- **Resilience**: circuit breaker, retry, timeout, bulkhead and fallbacks (Resilience4J)
- **Event-driven architecture**: asynchronous messaging with Apache Kafka
- **Distributed transactions**: Saga pattern, Outbox pattern, idempotency
- **Containerization**: Docker and Docker Compose
- **AI integration** *(later)*: natural-language search and RAG assistant with Spring AI

## Architecture

| Module | Role | Key technology                                         | Status |
|---|---|--------------------------------------------------------|---|
| `customer-service` | Customer profiles (CRUD) | Spring Web, JPA                                        | Done |
| `api-gateway` | Single entry point, routing, rate limiting | Spring Cloud Gateway                                   | Done |
| `discovery-server` | Service registry | Eureka                                                 | Done |
| `event-service` | Events catalog (create, publish, close) | Spring Web, JPA                                        | Done |
| `config-server` | Centralized configuration | Spring Cloud Config                                    | Done |
| `booking-service` | Booking, seat counting, payment orchestration; calls `event-service` and `payment-service` | Spring Web, JPA, OpenFeign, Resilience4J               | Done |
| `payment-service` | Simulated payment provider (outcome driven by the card token), idempotent charge and refund | Spring Web, JPA                                        | Done |
| `notification-service` | Confirmation emails/SMS | Kafka consumer                                         | Planned |
| `web-frontend` | Event catalog and customer booking journey | Angular                                                | Planned |
| `ai-assistant-service` | Read-only assistant via Telegram | Cloud AI provider + Telegram Bot API (to be confirmed) | Planned |

## Roadmap

Each step is developed on a `feature/step-XX-...` branch and frozen with a `step-XX-...` tag once merged. The numbering follows the cahier des charges; only steps already tagged are available.

| Step | Content |
|---|---|
| `step-00-setup` | Parent POM, repo structure, CI |
| `step-01-customer-service` | First business service |
| `step-02-gateway-static` | Gateway with static routes |
| `step-03-discovery` | Eureka + load-balanced routing |
| `step-04-event-service` | Second business service |
| `step-05-config` | Config Server |
| `step-06-booking-feign` | booking-service + OpenFeign (synchronous call to event-service) |
| `step-07-persistence` | PostgreSQL for event-service and booking-service (Docker volume, Testcontainers) |
| `step-08-resilience` | Resilience4J: circuit breaker on the booking -> event call |
| `step-09-payment` | Simulated payment service, booking pays through it |
| `step-10-api-contract` | `/api/v1`, OpenAPI, explicit gateway routes |
| `step-11-security` | Keycloak, JWT, roles |
| `step-12-catalog` | Catalog features (search, pagination), Flyway migrations |
| `step-13-customer-profile` | Customer profile bound to the identity |
| `step-14-kafka` | Versioned events, outbox, DLT |
| `step-15-saga` | Saga, idempotency |
| `step-16-ticket` | Ticket issuing |
| `step-17-notification` | notification-service |
| `step-18-observability` | Tracing and metrics |
| `step-19-mcp-tools` | MCP tool server |
| `step-20-assistant` | AI assistant |
| `step-21-telegram-link` | Telegram account linking |
| `step-22-reports` | Reports |
| `step-23-web-catalog` | Angular catalog |
| `step-24-web-client` | Angular customer journey |
| `step-25-web-admin` | Angular back-office |
| `step-26-docs-demo` | Documentation and demo |

To follow a lesson, check out its tag: `git checkout step-01-customer-service`.
Start order with Docker Compose is handled for you: postgres and config-server, discovery-server, then the services.
event-service, booking-service and payment-service (and customer-service) store their data in PostgreSQL (one database each, volume `postgres-data`).

## Tech stack

Java 21 · Spring Boot 4 · Spring Cloud 2025.1 · Resilience4J · Apache Kafka ·
PostgreSQL · Docker / Docker Compose · Spring AI (later)

## Repository structure

    ticketflow/
    ├── docs/            # lessons, ADRs, templates
    ├── infra/           # docker-compose and infrastructure config
    ├── config-repo/     # configuration served by the config server
    └── services/        # one folder per microservice, each with its own README

Every module is independently runnable and testable, and documents itself in
its own `README.md`.

## Getting started

Prerequisites: JDK 21, Maven (or the included wrapper), Docker.

    git clone https://github.com/<your-user>/ticketflow.git
    cd ticketflow
    mvn clean verify

## Who is this for?

Java developers who know Spring Boot basics and want to move into
microservices, and anyone preparing for backend or architecture interviews.

<!--
## Contributing

Contributions, issues and exercises are welcome. A `CONTRIBUTING.md` will be
added soon.
-->

## License

To be defined (MIT recommended for an educational project).

## Author

**AHAIDOUS Khadija**   | [GitHub](https://github.com/kahaidous) · [LinkedIn](https://www.linkedin.com/in/khadijaahaidous)
