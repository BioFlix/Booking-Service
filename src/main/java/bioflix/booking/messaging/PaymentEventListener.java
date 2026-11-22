package bioflix.booking.messaging;

import bioflix.booking.inbox.ProcessedEvent;
import bioflix.booking.inbox.ProcessedEventRepository;
import bioflix.booking.messaging.PaymentEvents.PaymentCompleted;
import bioflix.booking.messaging.PaymentEvents.PaymentFailed;
import bioflix.booking.service.BookingService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumes payment.completed and payment.failed from booking-service.payment-events.
 * <ul>
 *   <li>Idempotent: the eventId is saved in processed_events in the same transaction as the booking change,
 *       so a redelivered event is skipped.</li>
 *   <li>Invalid messages (bad JSON, missing ids) are rejected at once and go to the dead-letter queue.</li>
 *   <li>Other exceptions (database down, optimistic lock) are retried, then dead-lettered.</li>
 * </ul>
 * The message is parsed by hand (not by a message converter), so it works whatever content type the
 * producer sets.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final BookingService bookingService;
    private final ProcessedEventRepository processedEvents;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public PaymentEventListener(BookingService bookingService, ProcessedEventRepository processedEvents,
                                JsonMapper jsonMapper, Clock clock) {
        this.bookingService = bookingService;
        this.processedEvents = processedEvents;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @RabbitListener(queues = RabbitConfig.PAYMENT_QUEUE)
    @Transactional
    public void onMessage(Message message) {
        EventEnvelope<JsonNode> event = parse(message);

        if (processedEvents.existsById(event.eventId())) {
            log.info("Event {} ({}) already processed – skipped", event.eventId(), event.type());
            return;
        }

        switch (event.type()) {
            case EventTypes.PAYMENT_COMPLETED -> {
                PaymentCompleted data = data(event, PaymentCompleted.class);
                UUID bookingId = uuid(data.bookingId(), "bookingId", event);
                UUID paymentId = uuid(data.paymentId(), "paymentId", event);
                bookingService.handlePaymentCompleted(bookingId, paymentId);
            }
            case EventTypes.PAYMENT_FAILED -> {
                PaymentFailed data = data(event, PaymentFailed.class);
                UUID bookingId = uuid(data.bookingId(), "bookingId", event);
                bookingService.handlePaymentFailed(bookingId, data.reason());
            }
            default -> {
                log.warn("Unexpected event type {} on {} – ignored", event.type(), RabbitConfig.PAYMENT_QUEUE);
            }
        }

        processedEvents.save(new ProcessedEvent(event.eventId(), event.type(), clock.instant()));
    }

    private EventEnvelope<JsonNode> parse(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        IncomingEvent in;
        try {
            in = jsonMapper.readValue(body, IncomingEvent.class);
        } catch (JacksonException ex) {
            throw new AmqpRejectAndDontRequeueException("Unreadable event: " + ex.getOriginalMessage(), ex);
        }
        if (in == null || in.eventId() == null || in.type() == null || in.data() == null || !in.data().isObject()) {
            throw new AmqpRejectAndDontRequeueException("Event without eventId/type/data: " + body);
        }
        return new EventEnvelope<>(in.eventId(), in.type(), in.version(), in.occurredAt(), in.producer(),
                in.correlationId(), in.data());
    }

    /** The envelope as it arrives; data is kept as a JSON tree until we know the type. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncomingEvent(UUID eventId, String type, Integer version, Instant occurredAt, String producer,
                         String correlationId, JsonNode data) {
    }

    private <T> T data(EventEnvelope<JsonNode> event, Class<T> type) {
        try {
            return jsonMapper.treeToValue(event.data(), type);
        } catch (JacksonException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid data in " + event.type() + " " + event.eventId(), ex);
        }
    }

    private static UUID uuid(String value, String field, EventEnvelope<JsonNode> event) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException(
                    "Invalid " + field + " '" + value + "' in " + event.type() + " " + event.eventId(), ex);
        }
    }
}
