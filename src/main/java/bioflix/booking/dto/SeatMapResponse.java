package bioflix.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/bookings/showtimes/{id}/seats – the hall layout from catalog with each seat marked as taken or free.
 * Prices are in øre per seat type.
 */
public record SeatMapResponse(
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startsAt,
        String currency,
        Map<String, Integer> prices,
        List<Row> rows
) {

    public record Row(String label, List<Seat> seats) {
    }

    public record Seat(int number, String type, Integer price, boolean taken) {
    }
}
