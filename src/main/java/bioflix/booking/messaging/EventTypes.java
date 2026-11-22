package bioflix.booking.messaging;

/** Event types (= routing keys on cinema.events) that booking-service publishes or consumes. */
public final class EventTypes {

    // published by us
    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";

    // consumed by us (producer: payment-service)
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";

    private EventTypes() {
    }
}
