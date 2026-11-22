package bioflix.booking.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Our own view of a showtime from catalog-service – keeps the generated gRPC classes out of the service layer.
 *
 * @param prices seat type (STANDARD, VIP, WHEELCHAIR) -> price in øre
 * @param rows   the hall layout, row by row
 */
public record ShowtimeInfo(
        String showtimeId,
        String movieTitle,
        String hallName,
        Instant startsAt,
        String status,
        String currency,
        Map<String, Integer> prices,
        List<Row> rows
) {

    public record Row(String label, List<Seat> seats) {
    }

    public record Seat(int number, String type) {
    }

    public boolean isCancelled() {
        return "CANCELLED".equalsIgnoreCase(status);
    }

    public boolean hasStarted(Instant now) {
        return !startsAt.isAfter(now);
    }

    /** The seat type of row/number, or empty if the seat doesn't exist in this hall. */
    public Optional<String> seatType(String rowLabel, int number) {
        return rows.stream()
                .filter(r -> r.label().equalsIgnoreCase(rowLabel))
                .flatMap(r -> r.seats().stream())
                .filter(s -> s.number() == number)
                .map(Seat::type)
                .findFirst();
    }

    /** Price in øre for a seat type, or empty if catalog has no price for it. */
    public Optional<Integer> priceFor(String seatType) {
        return Optional.ofNullable(prices.get(seatType));
    }
}
