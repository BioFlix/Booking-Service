package bioflix.booking.entity;

/**
 * PENDING_PAYMENT --payment.completed--> CONFIRMED
 * PENDING_PAYMENT --payment.failed / timeout / user--> CANCELLED
 * CONFIRMED --user cancels--> CANCELLED (payment-service refunds)
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED
}
