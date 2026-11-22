package bioflix.booking.integration;

import bioflix.booking.client.CatalogClient;
import bioflix.booking.client.CatalogUnavailableException;
import bioflix.booking.client.ShowtimeInfo;
import bioflix.booking.dto.BookingResponse;
import bioflix.booking.dto.CreateBookingRequest;
import bioflix.booking.dto.CreateBookingRequest.SeatRequest;
import bioflix.booking.dto.SeatMapResponse;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.entity.CancelReason;
import bioflix.booking.exception.BadRequestException;
import bioflix.booking.exception.ConflictException;
import bioflix.booking.inbox.ProcessedEventRepository;
import bioflix.booking.messaging.RabbitConfig;
import bioflix.booking.service.BookingExpiryJob;
import bioflix.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real Postgres (Flyway schema) + RabbitMQ in Docker; Catalog is mocked.
 * Payment events are sent as raw JSON envelopes, exactly as payment-service would send them, and the booking.*
 * events we publish are read from a test queue bound to cinema.events – so this also checks the event contract.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-attempts=2",
        "app.outbox.poll-interval=200ms",
        "app.booking.expiry-interval=1h",      // the test runs the expiry job itself
        "app.booking.hold-minutes=15"
})
class BookingFlowIntegrationTest {

    private static final String EVENTS_TEST_QUEUE = "test.booking-events";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SHOW = "66f000000000000000000c01";
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final MutableClock CLOCK = new MutableClock();

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    @TestBean Clock clock;
    @MockitoBean CatalogClient catalogClient;

    @Autowired BookingService bookingService;
    @Autowired BookingExpiryJob expiryJob;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange eventsExchange;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper jsonMapper;

    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        CLOCK.reset();
        jdbc.execute("TRUNCATE booking_seats, bookings, outbox_events, processed_events");

        Queue events = new Queue(EVENTS_TEST_QUEUE, false, false, false);
        amqpAdmin.declareQueue(events);
        amqpAdmin.declareBinding(BindingBuilder.bind(events).to(eventsExchange).with("booking.#"));
        amqpAdmin.purgeQueue(EVENTS_TEST_QUEUE, false);

        when(catalogClient.getShowtime(anyString())).thenReturn(showtime(Instant.now().plus(Duration.ofDays(2)), "SCHEDULED"));
    }

    // ------------------------------------------------------------------ creating

    @Test
    void createHoldsSeatsWithCatalogPricesAndPublishesBookingCreated() {
        BookingResponse booking = book(ALICE, seat("F", 7), seat("f", 8));

        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.totalAmount()).isEqualTo(13500 + 17500);
        assertThat(booking.currency()).isEqualTo("DKK");
        assertThat(booking.movieTitle()).isEqualTo("Dune: Part Two");
        assertThat(booking.expiresAt()).isEqualTo(CLOCK.instant().plus(Duration.ofMinutes(15)));
        assertThat(booking.seats()).extracting(BookingResponse.SeatResponse::type).containsExactly("STANDARD", "VIP");

        JsonNode event = nextEvent("booking.created");
        assertThat(event.get("eventId").asString()).isNotBlank();
        assertThat(event.get("version").asInt()).isEqualTo(1);
        assertThat(event.get("producer").asString()).isEqualTo("booking-service");
        assertThat(event.get("correlationId").asString()).isEqualTo(booking.id().toString());
        JsonNode data = event.get("data");
        assertThat(data.get("bookingId").asString()).isEqualTo(booking.id().toString());
        assertThat(data.get("userId").asString()).isEqualTo(ALICE.toString());
        assertThat(data.get("showtimeId").asString()).isEqualTo(SHOW);
        assertThat(data.get("amount").asInt()).isEqualTo(31000);
        assertThat(data.get("currency").asString()).isEqualTo("DKK");
        assertThat(data.get("expiresAt").asString()).isNotBlank();
        assertThat(data.get("seats").get(1).get("row").asString()).isEqualTo("F");
        assertThat(data.get("seats").get(1).get("number").asInt()).isEqualTo(8);
        assertThat(data.get("seats").get(1).get("type").asString()).isEqualTo("VIP");
        assertThat(data.get("seats").get(1).get("price").asInt()).isEqualTo(17500);
    }

    @Test
    void aSeatCanOnlyBeHeldOnce() {
        book(ALICE, seat("F", 7));

        assertThatThrownBy(() -> book(BOB, seat("F", 8), seat("F", 7)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("F7");
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsADoubleBookingEvenWithoutTheFriendlyCheck() {
        book(ALICE, seat("F", 7));
        UUID other = UUID.randomUUID();
        jdbc.update("""
                insert into bookings (id, user_id, showtime_id, status, total_amount, currency, movie_title, hall_name,
                                      starts_at, expires_at, created_at, updated_at)
                values (?, ?, ?, 'PENDING_PAYMENT', 0, 'DKK', 'x', 'x', now(), now(), now(), now())""",
                other, BOB, SHOW);

        assertThatThrownBy(() -> jdbc.update("""
                insert into booking_seats (booking_id, showtime_id, seat_row, seat_number, seat_type, price)
                values (?, ?, 'F', 7, 'STANDARD', 13500)""", other, SHOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownSeatCancelledOrStartedShowtimeAndCatalogDownAreRejected() {
        assertThatThrownBy(() -> book(ALICE, seat("F", 99))).isInstanceOf(BadRequestException.class);

        when(catalogClient.getShowtime(anyString())).thenReturn(showtime(Instant.now().plus(Duration.ofDays(1)), "CANCELLED"));
        assertThatThrownBy(() -> book(ALICE, seat("F", 7))).isInstanceOf(ConflictException.class);

        when(catalogClient.getShowtime(anyString())).thenReturn(showtime(Instant.now().minus(Duration.ofMinutes(5)), "SCHEDULED"));
        assertThatThrownBy(() -> book(ALICE, seat("F", 7))).isInstanceOf(ConflictException.class);

        when(catalogClient.getShowtime(anyString())).thenThrow(new CatalogUnavailableException("down", null));
        assertThatThrownBy(() -> book(ALICE, seat("F", 7))).isInstanceOf(CatalogUnavailableException.class);

        assertThat(count("bookings")).isZero();
    }

    @Test
    void seatMapMarksHeldSeatsAsTaken() {
        book(ALICE, seat("F", 7));

        SeatMapResponse map = bookingService.getSeatMap(SHOW);

        assertThat(map.rows()).hasSize(1);
        assertThat(map.rows().getFirst().seats())
                .extracting(SeatMapResponse.Seat::number, SeatMapResponse.Seat::taken, SeatMapResponse.Seat::price)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(7, true, 13500),
                        org.assertj.core.groups.Tuple.tuple(8, false, 17500));
    }

    // ------------------------------------------------------------------ payment events

    @Test
    void paymentCompletedConfirmsOnceAndPublishesBookingConfirmed() {
        BookingResponse booking = book(ALICE, seat("F", 7));
        nextEvent("booking.created");

        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        sendPaymentCompleted(eventId, booking.id(), paymentId);

        await().atMost(WAIT).until(() -> status(booking.id()) == BookingStatus.CONFIRMED);
        JsonNode data = nextEvent("booking.confirmed").get("data");
        assertThat(data.get("bookingId").asString()).isEqualTo(booking.id().toString());
        assertThat(data.get("userId").asString()).isEqualTo(ALICE.toString());
        assertThat(data.get("showtimeId").asString()).isEqualTo(SHOW);
        assertThat(data.get("movieTitle").asString()).isEqualTo("Dune: Part Two");
        assertThat(data.get("hallName").asString()).isEqualTo("Sal 1");
        assertThat(data.get("startsAt").asString()).isNotBlank();
        assertThat(data.get("seats").get(0).get("price").asInt()).isEqualTo(13500);

        // the same event again (redelivery) is skipped
        sendPaymentCompleted(eventId, booking.id(), paymentId);
        await().atMost(WAIT).until(() -> processedEvents.count() == 1);
        assertThat(rabbitTemplate.receive(EVENTS_TEST_QUEUE, 1_000)).isNull();
    }

    @Test
    void paymentFailedCancelsAndReleasesTheSeats() {
        BookingResponse booking = book(ALICE, seat("F", 7));
        nextEvent("booking.created");

        sendEvent("payment.failed", UUID.randomUUID(), booking.id(), Map.of(
                "paymentId", UUID.randomUUID().toString(),
                "bookingId", booking.id().toString(),
                "userId", ALICE.toString(),
                "reason", "card_declined"));

        await().atMost(WAIT).until(() -> status(booking.id()) == BookingStatus.CANCELLED);
        JsonNode data = nextEvent("booking.cancelled").get("data");
        assertThat(data.get("reason").asString()).isEqualTo("PAYMENT_FAILED");
        assertThat(data.get("wasPaid").asBoolean()).isFalse();

        // the seat is free again
        assertThat(book(BOB, seat("F", 7)).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void invalidPaymentEventGoesToTheDeadLetterQueue() {
        amqpAdmin.purgeQueue(RabbitConfig.DEAD_LETTER_QUEUE, false);

        sendEvent("payment.completed", UUID.randomUUID(), UUID.randomUUID(), Map.of("bookingId", "not-a-uuid"));

        await().atMost(WAIT).until(() -> rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE, 200) != null);
    }

    // ------------------------------------------------------------------ hold time

    @Test
    void unpaidBookingExpiresAndReleasesTheSeats() {
        BookingResponse booking = book(ALICE, seat("F", 7));
        nextEvent("booking.created");

        CLOCK.advance(Duration.ofMinutes(14));
        expiryJob.expireBookings();
        assertThat(status(booking.id())).isEqualTo(BookingStatus.PENDING_PAYMENT);

        CLOCK.advance(Duration.ofMinutes(2));
        expiryJob.expireBookings();
        assertThat(status(booking.id())).isEqualTo(BookingStatus.EXPIRED);

        JsonNode data = nextEvent("booking.cancelled").get("data");
        assertThat(data.get("reason").asString()).isEqualTo("EXPIRED");
        assertThat(data.get("wasPaid").asBoolean()).isFalse();
        assertThat(book(BOB, seat("F", 7)).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void latePaymentIsNotConfirmedButRefunded() {
        BookingResponse booking = book(ALICE, seat("F", 7));
        nextEvent("booking.created");

        CLOCK.advance(Duration.ofMinutes(20));     // the expiry job hasn't run yet
        sendPaymentCompleted(UUID.randomUUID(), booking.id(), UUID.randomUUID());

        await().atMost(WAIT).until(() -> status(booking.id()) == BookingStatus.EXPIRED);
        JsonNode data = nextEvent("booking.cancelled").get("data");
        assertThat(data.get("reason").asString()).isEqualTo("EXPIRED");
        assertThat(data.get("wasPaid").asBoolean()).isTrue();
    }

    @Test
    void paymentAfterExpiryAsksForRefund() {
        BookingResponse booking = book(ALICE, seat("F", 7));
        nextEvent("booking.created");
        CLOCK.advance(Duration.ofMinutes(16));
        expiryJob.expireBookings();
        assertThat(nextEvent("booking.cancelled").get("data").get("wasPaid").asBoolean()).isFalse();

        sendPaymentCompleted(UUID.randomUUID(), booking.id(), UUID.randomUUID());

        JsonNode data = nextEvent("booking.cancelled").get("data");
        assertThat(data.get("reason").asString()).isEqualTo("EXPIRED");
        assertThat(data.get("wasPaid").asBoolean()).isTrue();
        assertThat(status(booking.id())).isEqualTo(BookingStatus.EXPIRED);
    }

    // ------------------------------------------------------------------ customer cancels

    @Test
    void customerCancelsAPaidBookingAndPaymentIsToldToRefund() {
        BookingResponse booking = book(ALICE, seat("F", 7));
        nextEvent("booking.created");
        sendPaymentCompleted(UUID.randomUUID(), booking.id(), UUID.randomUUID());
        await().atMost(WAIT).until(() -> status(booking.id()) == BookingStatus.CONFIRMED);
        nextEvent("booking.confirmed");

        assertThatThrownBy(() -> bookingService.cancelByUser(booking.id(), BOB))
                .isInstanceOf(bioflix.booking.exception.NotFoundException.class);

        BookingResponse cancelled = bookingService.cancelByUser(booking.id(), ALICE);

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.USER);
        JsonNode data = nextEvent("booking.cancelled").get("data");
        assertThat(data.get("reason").asString()).isEqualTo("USER");
        assertThat(data.get("wasPaid").asBoolean()).isTrue();
        assertThatThrownBy(() -> bookingService.cancelByUser(booking.id(), ALICE)).isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------------ helpers

    private BookingResponse book(UUID user, SeatRequest... seats) {
        return bookingService.createBooking(user, new CreateBookingRequest(SHOW, List.of(seats)));
    }

    private static SeatRequest seat(String row, int number) {
        return new SeatRequest(row, number);
    }

    private BookingStatus status(UUID bookingId) {
        return BookingStatus.valueOf(jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId));
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    /** Waits for the next booking.* event on the test queue and checks its type and routing key. */
    private JsonNode nextEvent(String expectedType) {
        Message message = rabbitTemplate.receive(EVENTS_TEST_QUEUE, WAIT.toMillis());
        assertThat(message).as("expected a %s event", expectedType).isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(expectedType);
        JsonNode event = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(event.get("type").asString()).isEqualTo(expectedType);
        return event;
    }

    private void sendPaymentCompleted(UUID eventId, UUID bookingId, UUID paymentId) {
        sendEvent("payment.completed", eventId, bookingId, Map.of(
                "paymentId", paymentId.toString(),
                "bookingId", bookingId.toString(),
                "userId", ALICE.toString(),
                "amount", 13500,
                "currency", "DKK",
                "receiptNumber", "1234-5678",
                "receiptUrl", "https://pay.stripe.com/receipts/test"));
    }

    private void sendEvent(String type, UUID eventId, UUID correlationId, Map<String, Object> data) {
        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "type", type,
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "producer", "payment-service",
                "correlationId", correlationId.toString(),
                "data", data);
        Message message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(eventsExchange.getName(), type, message);
    }

    private static ShowtimeInfo showtime(Instant startsAt, String status) {
        List<ShowtimeInfo.Seat> seats = new ArrayList<>();
        seats.add(new ShowtimeInfo.Seat(7, "STANDARD"));
        seats.add(new ShowtimeInfo.Seat(8, "VIP"));
        return new ShowtimeInfo(SHOW, "Dune: Part Two", "Sal 1", startsAt, status, "DKK",
                Map.of("STANDARD", 13500, "VIP", 17500), List.of(new ShowtimeInfo.Row("F", seats)));
    }
}
