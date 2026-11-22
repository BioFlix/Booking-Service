-- Booking-Service schema (see cinema-data-model.html).
-- Money is integer øre (13500 = 135,00 kr). All timestamps are UTC (timestamptz).

CREATE TABLE bookings (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL,                 -- from X-User-Id (user-service)
    showtime_id   VARCHAR(24) NOT NULL,                 -- Mongo ObjectId in catalog-service
    status        VARCHAR(20) NOT NULL
                  CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    total_amount  INTEGER     NOT NULL CHECK (total_amount >= 0),
    currency      VARCHAR(3)  NOT NULL,

    -- snapshot from catalog at booking time, so mails/tickets don't need to call catalog again
    movie_title   VARCHAR(255) NOT NULL,
    hall_name     VARCHAR(100) NOT NULL,
    starts_at     TIMESTAMPTZ  NOT NULL,

    expires_at    TIMESTAMPTZ NOT NULL,                 -- seats are held until then
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    confirmed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    cancel_reason VARCHAR(30)
                  CHECK (cancel_reason IN ('USER', 'PAYMENT_FAILED', 'EXPIRED', 'SHOWTIME_CANCELLED')),

    payment_id    UUID,                                 -- from payment.completed
    paid_at       TIMESTAMPTZ,                          -- set when payment.completed arrives (also if late)

    version       BIGINT      NOT NULL DEFAULT 0        -- optimistic locking (@Version)
);

CREATE INDEX idx_bookings_user ON bookings (user_id, created_at DESC);
CREATE INDEX idx_bookings_pending_expiry ON bookings (expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE booking_seats (
    id          BIGSERIAL   PRIMARY KEY,
    booking_id  UUID        NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    showtime_id VARCHAR(24) NOT NULL,
    seat_row    VARCHAR(2)  NOT NULL,
    seat_number INTEGER     NOT NULL CHECK (seat_number > 0),
    seat_type   VARCHAR(20) NOT NULL,                   -- STANDARD | VIP | WHEELCHAIR
    price       INTEGER     NOT NULL CHECK (price >= 0),
    released    BOOLEAN     NOT NULL DEFAULT FALSE      -- true when the booking is cancelled/expired
);

CREATE INDEX idx_booking_seats_booking ON booking_seats (booking_id);

-- The real guarantee against double booking: a seat can only be held by one active booking.
-- Released seats (cancelled/expired bookings) are kept as history and don't count.
CREATE UNIQUE INDEX uq_booking_seats_active
    ON booking_seats (showtime_id, seat_row, seat_number)
    WHERE NOT released;

-- Transactional outbox: events are written in the same transaction as the booking change
-- and published to RabbitMQ by OutboxRelay.
CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,              -- = envelope eventId
    aggregate_id UUID         NOT NULL,                 -- bookingId
    event_type   VARCHAR(64)  NOT NULL,                 -- = routing key
    payload      TEXT         NOT NULL,                 -- the full envelope as JSON
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   VARCHAR(1000)
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Idempotent consumers: eventIds of payment.* events we have already handled.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
