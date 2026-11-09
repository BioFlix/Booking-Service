package bioflix.booking.dto;

import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {
    }

    /** POST /api/bookings  body: { "showtimeId": "...", "seats": ["C7", "C8"] } */
    public record CreateBookingRequest(
            @NotBlank String showtimeId,
            @NotEmpty List<@Pattern(regexp = "^[A-Z][0-9]{1,2}$", message = "seat must look like C7") String> seats
    ) {
    }

    public record BookingResponse(
            Long id,
            Long userId,
            String showtimeId,
            String movieTitle,
            String hallName,
            Instant startTime,
            List<String> seats,
            BookingStatus status,
            long totalAmountMinor,
            String currency,
            String cancellationReason,
            Instant createdAt
    ) {
        public static BookingResponse from(Booking b) {
            return new BookingResponse(b.getId(), b.getUserId(), b.getShowtimeId(), b.getMovieTitle(),
                    b.getHallName(), b.getStartTime(), List.copyOf(b.getSeats()), b.getStatus(),
                    b.getTotalAmountMinor(), b.getCurrency(), b.getCancellationReason(), b.getCreatedAt());
        }
    }

    /** Which seats are taken for a showtime – the client greys these out in the seat map. */
    public record ReservedSeatsResponse(String showtimeId, List<String> reservedSeats) {
    }
}
