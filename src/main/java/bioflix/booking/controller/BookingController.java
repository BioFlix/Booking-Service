package bioflix.booking.controller;

import bioflix.booking.dto.BookingResponse;
import bioflix.booking.dto.CreateBookingRequest;
import bioflix.booking.dto.SeatMapResponse;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.exception.ForbiddenException;
import bioflix.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * X-User-Id (UUID) and X-User-Role are set by the API gateway after it has validated the Auth0 token with
 * user-service (/api/auth/validate). When testing locally without the gateway, send the headers yourself.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    static final String USER_ID = "X-User-Id";
    static final String USER_ROLE = "X-User-Role";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Body: { "showtimeId": "...", "seats": [ { "row": "F", "number": 7 } ] } */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@RequestHeader(USER_ID) UUID userId,
                                  @Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createBooking(userId, request);
    }

    @GetMapping("/me")
    public List<BookingResponse> myBookings(@RequestHeader(USER_ID) UUID userId) {
        return bookingService.getByUser(userId);
    }

    /** The owner – or an admin – can see a booking. */
    @GetMapping("/{id}")
    public BookingResponse getById(@RequestHeader(USER_ID) UUID userId,
                                   @RequestHeader(value = USER_ROLE, required = false) String role,
                                   @PathVariable UUID id) {
        return bookingService.getForUser(id, userId, isAdmin(role));
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@RequestHeader(USER_ID) UUID userId, @PathVariable UUID id) {
        return bookingService.cancelByUser(id, userId);
    }

    /** Admin: all bookings, newest first, optionally filtered by status. */
    @GetMapping
    public List<BookingResponse> list(@RequestHeader(value = USER_ROLE, required = false) String role,
                                      @RequestParam(required = false) BookingStatus status,
                                      @RequestParam(defaultValue = "100") int limit) {
        if (!isAdmin(role)) {
            throw new ForbiddenException("Only admins can list all bookings");
        }
        return bookingService.list(status, limit);
    }

    /** Public: the hall layout with taken seats marked (for the seat picker). */
    @GetMapping("/showtimes/{showtimeId}/seats")
    public SeatMapResponse seatMap(@PathVariable String showtimeId) {
        return bookingService.getSeatMap(showtimeId);
    }

    private static boolean isAdmin(String role) {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
