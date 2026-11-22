package bioflix.booking.messaging;

import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingSeat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The data part of the events we publish, exactly as in the event contract (cinema-data-model.html).
 * Extra fields are allowed – consumers ignore what they don't know.
 */
public final class BookingEvents {

    private BookingEvents() {
    }

    /** seats[] in events: { "row": "F", "number": 7, "type": "STANDARD", "price": 13500 } */
    public record SeatData(String row, int number, String type, int price) {
    }

    /** booking.created (→ payment-service). amount in øre. */
    public record BookingCreated(
            UUID bookingId,
            UUID userId,
            String showtimeId,
            int amount,
            String currency,
            List<SeatData> seats,
            Instant expiresAt
    ) {
        public static BookingCreated from(Booking b) {
            return new BookingCreated(b.getId(), b.getUserId(), b.getShowtimeId(), b.getTotalAmount(),
                    b.getCurrency(), seatsOf(b), b.getExpiresAt());
        }
    }

    /**
     * booking.confirmed (→ ticket-service, notification-service).
     * totalAmount and currency are optional extras that notification-service uses in the mail.
     */
    public record BookingConfirmed(
            UUID bookingId,
            UUID userId,
            String showtimeId,
            String movieTitle,
            String hallName,
            Instant startsAt,
            List<SeatData> seats,
            int totalAmount,
            String currency
    ) {
        public static BookingConfirmed from(Booking b) {
            return new BookingConfirmed(b.getId(), b.getUserId(), b.getShowtimeId(), b.getMovieTitle(),
                    b.getHallName(), b.getStartsAt(), seatsOf(b), b.getTotalAmount(), b.getCurrency());
        }
    }

    /**
     * booking.cancelled (→ payment-service, ticket-service, notification-service).
     * reason: USER | PAYMENT_FAILED | EXPIRED | SHOWTIME_CANCELLED. wasPaid = true means payment-service must refund.
     * movieTitle and startsAt are optional extras for the mail.
     */
    public record BookingCancelled(
            UUID bookingId,
            UUID userId,
            String reason,
            boolean wasPaid,
            String movieTitle,
            Instant startsAt
    ) {
        public static BookingCancelled from(Booking b) {
            return new BookingCancelled(b.getId(), b.getUserId(), b.getCancelReason().name(), b.isPaid(),
                    b.getMovieTitle(), b.getStartsAt());
        }
    }

    private static List<SeatData> seatsOf(Booking b) {
        return b.getSeats().stream().map(BookingEvents::seat).toList();
    }

    private static SeatData seat(BookingSeat s) {
        return new SeatData(s.getSeatRow(), s.getSeatNumber(), s.getSeatType(), s.getPrice());
    }
}
