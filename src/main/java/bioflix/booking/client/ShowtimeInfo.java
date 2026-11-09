package bioflix.booking.client;

import java.time.Instant;

/** Our own view of a showtime – keeps generated gRPC classes out of the service layer. */
public record ShowtimeInfo(
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startTime,
        long priceMinor,
        String currency,
        int rows,
        int seatsPerRow
) {
}
