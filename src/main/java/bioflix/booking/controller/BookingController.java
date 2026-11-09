package bioflix.booking.controller;

import bioflix.booking.dto.BookingDtos.BookingResponse;
import bioflix.booking.dto.BookingDtos.CreateBookingRequest;
import bioflix.booking.dto.BookingDtos.ReservedSeatsResponse;
import bioflix.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * X-User-Id is set by the API gateway after it has validated the token with user-service.
 * When testing locally without the gateway, just send the header yourself.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@RequestHeader("X-User-Id") Long userId,
                                  @Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createBooking(userId, request);
    }

    @GetMapping("/me")
    public List<BookingResponse> myBookings(@RequestHeader("X-User-Id") Long userId) {
        return bookingService.getByUser(userId);
    }

    @GetMapping("/{id}")
    public BookingResponse getById(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return bookingService.getForUser(id, userId);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return bookingService.cancelByUser(id, userId);
    }

    /** Public: which seats are taken for a showtime (for the seat map). */
    @GetMapping("/showtimes/{showtimeId}/reserved-seats")
    public ReservedSeatsResponse reservedSeats(@PathVariable String showtimeId) {
        return bookingService.getReservedSeats(showtimeId);
    }
}
