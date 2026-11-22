package bioflix.booking.service;

import bioflix.booking.client.CatalogClient;
import bioflix.booking.client.ShowtimeInfo;
import bioflix.booking.dto.BookingResponse;
import bioflix.booking.dto.CreateBookingRequest;
import bioflix.booking.dto.CreateBookingRequest.SeatRequest;
import bioflix.booking.dto.SeatMapResponse;
import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.entity.CancelReason;
import bioflix.booking.exception.BadRequestException;
import bioflix.booking.exception.ConflictException;
import bioflix.booking.exception.NotFoundException;
import bioflix.booking.messaging.BookingEvents.BookingCancelled;
import bioflix.booking.messaging.BookingEvents.BookingConfirmed;
import bioflix.booking.messaging.BookingEvents.BookingCreated;
import bioflix.booking.messaging.EventTypes;
import bioflix.booking.outbox.OutboxService;
import bioflix.booking.repository.BookingRepository;
import bioflix.booking.repository.BookingSeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository seatRepository;
    private final CatalogClient catalogClient;
    private final OutboxService outbox;
    private final Clock clock;
    private final Duration holdTime;
    private final int maxSeatsPerBooking;

    public BookingService(BookingRepository bookingRepository,
                          BookingSeatRepository seatRepository,
                          CatalogClient catalogClient,
                          OutboxService outbox,
                          Clock clock,
                          @Value("${app.booking.hold-minutes:15}") long holdMinutes,
                          @Value("${app.booking.max-seats-per-booking:10}") int maxSeatsPerBooking) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.catalogClient = catalogClient;
        this.outbox = outbox;
        this.clock = clock;
        this.holdTime = Duration.ofMinutes(holdMinutes);
        this.maxSeatsPerBooking = maxSeatsPerBooking;
    }

    // ================================================================ customer commands

    /**
     * Reserves the seats (status PENDING_PAYMENT, held until expiresAt) and publishes booking.created,
     * so payment-service creates a pending payment.
     */
    @Transactional
    public BookingResponse createBooking(UUID userId, CreateBookingRequest request) {
        List<SeatRequest> seats = normalise(request.seats());
        if (seats.size() > maxSeatsPerBooking) {
            throw new BadRequestException("You can book at most " + maxSeatsPerBooking + " seats at a time");
        }

        // 1) Synchronous gRPC call to catalog (404 if unknown, 503 if catalog is down)
        ShowtimeInfo showtime = catalogClient.getShowtime(request.showtimeId());
        Instant now = clock.instant();
        if (showtime.isCancelled()) {
            throw new ConflictException("This showtime has been cancelled");
        }
        if (showtime.hasStarted(now)) {
            throw new ConflictException("This showtime has already started");
        }

        // 2) Seat type and price come from catalog, never from the client
        Booking booking = new Booking(userId, showtime.showtimeId(), showtime.movieTitle(), showtime.hallName(),
                showtime.startsAt(), showtime.currency(), now.plus(holdTime));
        for (SeatRequest seat : seats) {
            String type = showtime.seatType(seat.row(), seat.number())
                    .orElseThrow(() -> new BadRequestException(
                            "Seat " + seat.row() + seat.number() + " does not exist in " + showtime.hallName()));
            int price = showtime.priceFor(type)
                    .orElseThrow(() -> new ConflictException("No price for seat type " + type + " in this showtime"));
            booking.addSeat(seat.row(), seat.number(), type, price);
        }

        // 3) Friendly check first – the partial unique index is the real guarantee (see BookingSeat)
        Set<String> taken = seatRepository.findTaken(showtime.showtimeId()).stream()
                .map(s -> key(s.getSeatRow(), s.getSeatNumber()))
                .collect(Collectors.toSet());
        List<String> clash = seats.stream().map(s -> key(s.row(), s.number())).filter(taken::contains).toList();
        if (!clash.isEmpty()) {
            throw new ConflictException("Seats already taken: " + clash);
        }

        // 4) Save – flush now so a race on the same seat becomes a 409 here, not at commit
        bookingRepository.saveAndFlush(booking);

        // 5) booking.created in the same transaction (outbox)
        outbox.enqueue(EventTypes.BOOKING_CREATED, booking.getId(), BookingCreated.from(booking));

        log.info("Booking {} created for user {} – {} seat(s), {} {} (held until {})", booking.getId(), userId,
                seats.size(), booking.getTotalAmount(), booking.getCurrency(), booking.getExpiresAt());
        return BookingResponse.from(booking);
    }

    /** Customer cancels. Allowed while pending or confirmed and before the showtime starts. */
    @Transactional
    public BookingResponse cancelByUser(UUID bookingId, UUID userId) {
        Booking booking = findOwnedOrThrow(bookingId, userId);
        if (!booking.getStatus().isActive()) {
            throw new ConflictException("Booking is already " + booking.getStatus());
        }
        if (booking.getStartsAt().isBefore(clock.instant())) {
            throw new ConflictException("Cannot cancel a booking for a showtime that has started");
        }
        cancel(booking, CancelReason.USER);
        return BookingResponse.from(booking);
    }

    // ================================================================ payment events

    /**
     * payment.completed.
     * <ul>
     *   <li>PENDING_PAYMENT and still within the hold time → CONFIRMED + booking.confirmed.</li>
     *   <li>Hold time passed (late payment) → don't confirm: EXPIRED + booking.cancelled with wasPaid = true,
     *       so payment-service refunds.</li>
     *   <li>Already CANCELLED/EXPIRED and not paid before → booking.cancelled again with wasPaid = true (refund).</li>
     *   <li>Already CONFIRMED → duplicate, nothing to do.</li>
     * </ul>
     */
    @Transactional
    public void handlePaymentCompleted(UUID bookingId, UUID paymentId) {
        Booking booking = bookingRepository.findWithSeatsById(bookingId).orElse(null);
        if (booking == null) {
            log.warn("payment.completed for unknown booking {} – ignored", bookingId);
            return;
        }
        Instant now = clock.instant();

        switch (booking.getStatus()) {
            case CONFIRMED -> log.info("payment.completed for booking {} that is already confirmed – ignored", bookingId);

            case PENDING_PAYMENT -> {
                booking.markPaid(paymentId, now);
                if (booking.isExpiredAt(now)) {
                    log.warn("Late payment for booking {} (expired at {}) – expiring and asking for a refund",
                            bookingId, booking.getExpiresAt());
                    booking.expire(now);
                    outbox.enqueue(EventTypes.BOOKING_CANCELLED, bookingId, BookingCancelled.from(booking));
                } else {
                    booking.confirm(now);
                    outbox.enqueue(EventTypes.BOOKING_CONFIRMED, bookingId, BookingConfirmed.from(booking));
                    log.info("Booking {} confirmed (payment {})", bookingId, paymentId);
                }
            }

            case CANCELLED, EXPIRED -> {
                if (booking.isPaid()) {
                    log.info("payment.completed for booking {} that is {} and already paid – ignored",
                            bookingId, booking.getStatus());
                    return;
                }
                log.warn("Payment arrived for booking {} that is already {} – asking for a refund",
                        bookingId, booking.getStatus());
                booking.markPaid(paymentId, now);
                outbox.enqueue(EventTypes.BOOKING_CANCELLED, bookingId, BookingCancelled.from(booking));
            }
        }
        bookingRepository.flush();
    }

    /** payment.failed: a pending booking is cancelled (PAYMENT_FAILED) and the seats are released. */
    @Transactional
    public void handlePaymentFailed(UUID bookingId, String reason) {
        Booking booking = bookingRepository.findWithSeatsById(bookingId).orElse(null);
        if (booking == null) {
            log.warn("payment.failed for unknown booking {} – ignored", bookingId);
            return;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            log.info("payment.failed for booking {} in status {} – ignored", bookingId, booking.getStatus());
            return;
        }
        log.info("Payment failed for booking {} ({}) – cancelling", bookingId, reason);
        cancel(booking, CancelReason.PAYMENT_FAILED);
        bookingRepository.flush();
    }

    // ================================================================ expiry

    /** Ids of pending bookings whose hold time has passed (used by BookingExpiryJob). */
    @Transactional(readOnly = true)
    public List<UUID> findExpiredPendingIds() {
        return bookingRepository.findIdsByStatusAndExpiresAtBefore(BookingStatus.PENDING_PAYMENT, clock.instant());
    }

    /**
     * EXPIRED + booking.cancelled (EXPIRED). Re-checks the state, because payment.completed may have arrived
     * after the job picked the id. Returns false if nothing was done.
     */
    @Transactional
    public boolean expire(UUID bookingId) {
        Booking booking = bookingRepository.findWithSeatsById(bookingId).orElse(null);
        Instant now = clock.instant();
        if (booking == null || booking.getStatus() != BookingStatus.PENDING_PAYMENT || !booking.isExpiredAt(now)) {
            return false;
        }
        booking.expire(now);
        outbox.enqueue(EventTypes.BOOKING_CANCELLED, bookingId, BookingCancelled.from(booking));
        bookingRepository.flush();   // optimistic lock conflict shows up here, inside this booking's transaction
        log.info("Booking {} expired – seats released", bookingId);
        return true;
    }

    // ================================================================ queries

    @Transactional(readOnly = true)
    public BookingResponse getForUser(UUID bookingId, UUID userId, boolean admin) {
        Booking booking = admin ? findOrThrow(bookingId) : findOwnedOrThrow(bookingId, userId);
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getByUser(UUID userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(BookingResponse::from).toList();
    }

    /** Admin list, newest first. */
    @Transactional(readOnly = true)
    public List<BookingResponse> list(BookingStatus status, int limit) {
        Limit max = Limit.of(Math.clamp(limit, 1, 500));
        List<Booking> bookings = status == null
                ? bookingRepository.findAllByOrderByCreatedAtDesc(max)
                : bookingRepository.findByStatusOrderByCreatedAtDesc(status, max);
        return bookings.stream().map(BookingResponse::from).toList();
    }

    /** The hall layout from catalog with the seats held by pending/confirmed bookings marked as taken. */
    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(String showtimeId) {
        ShowtimeInfo showtime = catalogClient.getShowtime(showtimeId);
        Set<String> taken = seatRepository.findTaken(showtime.showtimeId()).stream()
                .map(s -> key(s.getSeatRow(), s.getSeatNumber()))
                .collect(Collectors.toSet());

        List<SeatMapResponse.Row> rows = showtime.rows().stream()
                .map(row -> new SeatMapResponse.Row(row.label(), row.seats().stream()
                        .map(seat -> new SeatMapResponse.Seat(seat.number(), seat.type(),
                                showtime.priceFor(seat.type()).orElse(null),
                                taken.contains(key(row.label(), seat.number()))))
                        .toList()))
                .toList();

        return new SeatMapResponse(showtime.showtimeId(), showtime.movieTitle(), showtime.hallName(),
                showtime.startsAt(), showtime.currency(), showtime.prices(), rows);
    }

    // ================================================================ helpers

    private void cancel(Booking booking, CancelReason reason) {
        booking.cancel(reason, clock.instant());
        outbox.enqueue(EventTypes.BOOKING_CANCELLED, booking.getId(), BookingCancelled.from(booking));
        log.info("Booking {} cancelled ({}, wasPaid={}) – seats released", booking.getId(), reason, booking.isPaid());
    }

    /** Upper-case rows, drop duplicates, keep the order the customer picked. */
    private static List<SeatRequest> normalise(List<SeatRequest> seats) {
        Set<String> seen = new LinkedHashSet<>();
        List<SeatRequest> result = new ArrayList<>();
        for (SeatRequest s : seats) {
            String row = s.row().trim().toUpperCase(Locale.ROOT);
            if (seen.add(key(row, s.number()))) {
                result.add(new SeatRequest(row, s.number()));
            }
        }
        return result;
    }

    private static String key(String row, int number) {
        return row.toUpperCase(Locale.ROOT) + number;
    }

    private Booking findOrThrow(UUID id) {
        return bookingRepository.findWithSeatsById(id)
                .orElseThrow(() -> new NotFoundException("Booking " + id + " not found"));
    }

    /** Another user's booking looks exactly like a missing one (don't reveal that it exists). */
    private Booking findOwnedOrThrow(UUID id, UUID userId) {
        Booking booking = findOrThrow(id);
        if (!booking.getUserId().equals(userId)) {
            throw new NotFoundException("Booking " + id + " not found");
        }
        return booking;
    }
}
