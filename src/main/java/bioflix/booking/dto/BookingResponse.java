package bioflix.booking.dto;

import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingSeat;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.entity.CancelReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Amounts are in øre (13500 = 135,00 kr). Times are UTC. */
public record BookingResponse(
        UUID id,
        UUID userId,
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startsAt,
        BookingStatus status,
        List<SeatResponse> seats,
        int totalAmount,
        String currency,
        Instant expiresAt,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt,
        CancelReason cancelReason,
        boolean paid
) {

    public record SeatResponse(String row, int number, String type, int price) {
        static SeatResponse from(BookingSeat s) {
            return new SeatResponse(s.getSeatRow(), s.getSeatNumber(), s.getSeatType(), s.getPrice());
        }
    }

    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getUserId(), b.getShowtimeId(), b.getMovieTitle(), b.getHallName(),
                b.getStartsAt(), b.getStatus(), b.getSeats().stream().map(SeatResponse::from).toList(),
                b.getTotalAmount(), b.getCurrency(), b.getExpiresAt(), b.getCreatedAt(), b.getConfirmedAt(),
                b.getCancelledAt(), b.getCancelReason(), b.isPaid());
    }
}
