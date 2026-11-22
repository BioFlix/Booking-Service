package bioflix.booking.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The data part of the payment events we consume (producer: payment-service). Ids are UUID strings. */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** payment.completed: paymentId, bookingId, userId, amount, currency, receiptNumber, receiptUrl */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentCompleted(
            String paymentId,
            String bookingId,
            String userId,
            Integer amount,
            String currency,
            String receiptNumber,
            String receiptUrl
    ) {
    }

    /** payment.failed: paymentId, bookingId, userId, reason */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentFailed(
            String paymentId,
            String bookingId,
            String userId,
            String reason
    ) {
    }
}
