package bioflix.booking.entity;

/** reason in booking.cancelled (event contract). */
public enum CancelReason {
    USER,
    PAYMENT_FAILED,
    EXPIRED,
    SHOWTIME_CANCELLED
}
