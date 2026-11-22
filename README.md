# booking-service

Reservationer og sæder. Port **8083**, PostgreSQL `bookings_db`. Henter forestilling, sal og priser fra
catalog-service via gRPC og taler med resten via RabbitMQ (`cinema.events`).

**Kør `mvn compile` første gang** – gRPC-stubben genereres fra `src/main/proto/catalog.proto`
(kopieret uændret fra Catalog-Service – ret den aldrig kun ét sted).

## Kør lokalt
- Postgres med databasen `bookings_db` (postgres/postgres – kan overskrives med `DB_USER`/`DB_PASSWORD`).
  Tabellerne oprettes af **Flyway** (`src/main/resources/db/migration`), ikke af Hibernate.
- RabbitMQ på 5672 (guest/guest).
- Catalog-Service kørende (gRPC på 9090), ellers svarer booking-endpoints 503.
- Health: http://localhost:8083/actuator/health

| Variabel | Standard | |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | localhost / 5432 / bookings_db | |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | localhost / 5672 | |
| `CATALOG_GRPC_HOST` / `CATALOG_GRPC_PORT` | localhost / 9090 | |
| `BOOKING_HOLD_MINUTES` | 15 | Hvor længe sæderne holdes, mens kunden betaler (gruppen skal vælge 15 eller 30) |

## Endpoints
`X-User-Id` (UUID) og `X-User-Role` sættes af gateway'en. Uden gateway sender du dem selv.

| Metode | Sti | Beskrivelse |
|---|---|---|
| POST | `/api/bookings` | `{"showtimeId":"...","seats":[{"row":"F","number":7}]}` → 201, status `PENDING_PAYMENT` |
| GET | `/api/bookings/me` | Mine bookinger |
| GET | `/api/bookings/{id}` | Én booking (ejeren eller ADMIN) |
| POST | `/api/bookings/{id}/cancel` | Kunden annullerer (refunderes hvis betalt) |
| GET | `/api/bookings?status=&limit=` | Alle bookinger – kræver `X-User-Role: ADMIN` |
| GET | `/api/bookings/showtimes/{showtimeId}/seats` | Sædekort fra Catalog med optagede sæder markeret |

Sædetype og pris kommer fra Catalogs prisliste pr. sædetype – aldrig fra klienten. Beløb er i øre.

Fejl: 400 ugyldigt sæde/request · 401 mangler `X-User-Id` · 404 ukendt booking/forestilling ·
409 sædet er optaget, forestillingen er startet/aflyst · 503 Catalog er nede (timeout 2 s + circuit breaker).

## Status
`PENDING_PAYMENT` → `CONFIRMED` (payment.completed) · `CANCELLED` (kunden / payment.failed) ·
`EXPIRED` (ikke betalt inden `expires_at`, udløbsjobbet kører hvert minut).

Dobbeltbooking forhindres af det partielle unikke indeks
`(showtime_id, seat_row, seat_number) WHERE NOT released` på `booking_seats`. Annullerede/udløbne sæder
markeres `released` og bliver liggende som historik.

## Events (`cinema.events`, envelope `{eventId, type, version, occurredAt, producer, correlationId, data}`)
Publiceres via **outbox** (`outbox_events` + `OutboxRelay`), correlationId = bookingId:

| Event | data |
|---|---|
| `booking.created` | bookingId, userId, showtimeId, amount, currency, seats[], expiresAt |
| `booking.confirmed` | bookingId, userId, showtimeId, movieTitle, hallName, startsAt, seats[] (+ totalAmount, currency) |
| `booking.cancelled` | bookingId, userId, reason (USER / PAYMENT_FAILED / EXPIRED), wasPaid (+ movieTitle, startsAt) |

`seats[]`: `{"row":"F","number":7,"type":"STANDARD","price":13500}`

Lytter på kø `booking-service.payment-events` (idempotent via `processed_events`, dead-letter i
`booking-service.dead-letter`):
- `payment.completed` → `CONFIRMED` + booking.confirmed. Kommer betalingen efter `expires_at`, bekræftes
  bookingen ikke: den bliver `EXPIRED`, og der sendes booking.cancelled med `wasPaid: true`, så Payment refunderer.
- `payment.failed` → `CANCELLED` (PAYMENT_FAILED), sæderne frigives.

## Tests
`mvn verify` – unit-tests, `@WebMvcTest` og en integrationstest med Postgres og RabbitMQ i Testcontainers
(kræver Docker Desktop).

## CI
`.github/workflows/ci.yml` kører testene og pusher `<DOCKERHUB_USERNAME>/booking-service:latest` til Docker Hub
på `main`. Kræver repo-secrets `DOCKERHUB_USERNAME` og `DOCKERHUB_TOKEN`.
