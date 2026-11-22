package bioflix.booking.entity;

/**
 * PENDING_PAYMENT --payment.completed--> CONFIRMED
 * PENDING_PAYMENT --payment.failed / customer--> CANCELLED
 * PENDING_PAYMENT --hold time passed--> EXPIRED
 * CONFIRMED --customer cancels--> CANCELLED (payment-service refunds, wasPaid = true)
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    /** Seats are held only while the booking is pending or confirmed. */
    public boolean isActive() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
