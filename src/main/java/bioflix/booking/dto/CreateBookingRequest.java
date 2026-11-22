package bioflix.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * POST /api/bookings
 * <pre>{ "showtimeId": "66f0...", "seats": [ { "row": "F", "number": 7 }, { "row": "F", "number": 8 } ] }</pre>
 * Seat type and price are looked up in catalog – the client never decides the price.
 */
public record CreateBookingRequest(
        @NotBlank String showtimeId,
        @NotEmpty List<@Valid @NotNull SeatRequest> seats
) {

    public record SeatRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{1,2}$", message = "row must be a letter, e.g. F") String row,
            @NotNull @Min(1) @Max(999) Integer number
    ) {
    }
}
