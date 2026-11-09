package bioflix.booking.service;

import bioflix.booking.client.CatalogClient;
import bioflix.booking.client.ShowtimeInfo;
import bioflix.booking.dto.BookingDtos.BookingResponse;
import bioflix.booking.dto.BookingDtos.CreateBookingRequest;
import bioflix.booking.dto.BookingDtos.ReservedSeatsResponse;
import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.entity.SeatReservation;
import bioflix.booking.exception.ConflictException;
import bioflix.booking.exception.NotFoundException;
import bioflix.booking.repository.BookingRepository;
import bioflix.booking.repository.SeatReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final CatalogClient catalogClient;
    private final int maxSeatsPerBooking;

    public BookingService(BookingRepository bookingRepository,
                          SeatReservationRepository seatReservationRepository,
                          CatalogClient catalogClient,
                          @Value("${app.booking.max-seats-per-booking:10}") int maxSeatsPerBooking) {
        this.bookingRepository = bookingRepository;
        this.seatReservationRepository = seatReservationRepository;
        this.catalogClient = catalogClient;
        this.maxSeatsPerBooking = maxSeatsPerBooking;
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public BookingResponse createBooking(Long userId, CreateBookingRequest request) {
        Set<String> seats = new LinkedHashSet<>(request.seats());   // removes duplicates, keeps order
        if (seats.size() > maxSeatsPerBooking) {
            throw new ConflictException("You can book at most " + maxSeatsPerBooking + " seats at a time");
        }

        // 1) Synchronous gRPC call to catalog
        ShowtimeInfo showtime = catalogClient.getShowtime(request.showtimeId());

        if (showtime.startTime().isBefore(Instant.now())) {
            throw new ConflictException("This showtime has already started");
        }
        for (String seat : seats) {
            if (!SeatLabels.isValid(seat, showtime.rows(), showtime.seatsPerRow())) {
                throw new ConflictException("Seat " + seat + " does not exist in " + showtime.hallName());
            }
        }

        // 2) Friendly check first – the unique constraint is the real guarantee
        List<SeatReservation> taken = seatReservationRepository.findByShowtimeIdAndSeatLabelIn(showtime.showtimeId(), seats);
        if (!taken.isEmpty()) {
            throw new ConflictException("Seats already taken: " + taken.stream().map(SeatReservation::getSeatLabel).toList());
        }

        // 3) Save booking + seat locks
        long total = showtime.priceMinor() * seats.size();
        Booking booking = bookingRepository.save(new Booking(userId, showtime.showtimeId(), showtime.movieTitle(),
                showtime.hallName(), showtime.startTime(), List.copyOf(seats), total, showtime.currency()));

        seats.forEach(seat -> seatReservationRepository.save(new SeatReservation(showtime.showtimeId(), seat, booking.getId())));
        seatReservationRepository.flush();   // fails here (409) if someone grabbed a seat a millisecond ago

        // 4) TODO (RabbitMQ): publish booking.created – payment-service then starts a Stripe checkout

        return BookingResponse.from(booking);
    }

    /** Will be called when payment.completed arrives. Idempotent – safe if the message is delivered twice. */
    @Transactional
    public void confirm(Long bookingId) {
        Booking booking = findOrThrow(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            log.warn("Ignoring confirm for booking {} in status {}", bookingId, booking.getStatus());
            return;
        }
        booking.confirm();
        // TODO (RabbitMQ): publish booking.confirmed – ticket-service issues tickets, notification sends e-mail
    }

    /** Called by the user, the expiry job – and later by payment.failed. Releases the seats. */
    @Transactional
    public BookingResponse cancel(Long bookingId, String reason) {
        Booking booking = findOrThrow(bookingId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return BookingResponse.from(booking);
        }

        booking.cancel(reason);
        seatReservationRepository.deleteByBookingId(bookingId);

        // TODO (RabbitMQ): publish booking.cancelled – payment-service refunds if paid, ticket-service voids tickets

        return BookingResponse.from(booking);
    }

    @Transactional
    public BookingResponse cancelByUser(Long bookingId, Long userId) {
        Booking booking = findOrThrow(bookingId);
        if (!booking.getUserId().equals(userId)) {
            throw new NotFoundException("Booking " + bookingId + " not found");   // don't reveal other users' bookings
        }
        if (booking.getStartTime() != null && booking.getStartTime().isBefore(Instant.now())) {
            throw new ConflictException("Cannot cancel a booking for a showtime that has started");
        }
        return cancel(bookingId, "Cancelled by customer");
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public BookingResponse getForUser(Long bookingId, Long userId) {
        Booking booking = findOrThrow(bookingId);
        if (!booking.getUserId().equals(userId)) {
            throw new NotFoundException("Booking " + bookingId + " not found");
        }
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getByUser(Long userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(BookingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReservedSeatsResponse getReservedSeats(String showtimeId) {
        List<String> seats = seatReservationRepository.findByShowtimeId(showtimeId).stream()
                .map(SeatReservation::getSeatLabel).sorted().toList();
        return new ReservedSeatsResponse(showtimeId, seats);
    }

    private Booking findOrThrow(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking " + id + " not found"));
    }
}
