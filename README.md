# booking-service

Reservationer og sæder. Port **8083**, PostgreSQL `bookings_db`. Henter showtime-data fra catalog-service via gRPC.

**Kør `mvn compile` første gang** – gRPC-stubben genereres fra `src/main/proto/catalog.proto`.

## Endpoints (header `X-User-Id` sættes af gateway'en)
| Metode | Sti | Beskrivelse |
|---|---|---|
| POST | `/api/bookings` | `{"showtimeId":"...","seats":["C7","C8"]}` → status `PENDING_PAYMENT` |
| GET | `/api/bookings/me` | Mine bookinger |
| GET | `/api/bookings/{id}` | Én booking |
| POST | `/api/bookings/{id}/cancel` | Annullér (refunderes hvis betalt) |
| GET | `/api/bookings/showtimes/{showtimeId}/reserved-seats` | Optagede sæder til sædekortet |

Dobbeltbooking forhindres af en unik constraint på `(showtime_id, seat_label)` i `seat_reservations`.
Ubetalte bookinger annulleres efter `app.booking.payment-timeout-minutes` (15).

## Events (når RabbitMQ-repoet er klar)
Skal publicere `booking.created`, `booking.confirmed`, `booking.cancelled`.
Skal lytte på `payment.completed` (→ `BookingService.confirm`) og `payment.failed` (→ `BookingService.cancel`).
Se `TODO (RabbitMQ)` i `BookingService`.
