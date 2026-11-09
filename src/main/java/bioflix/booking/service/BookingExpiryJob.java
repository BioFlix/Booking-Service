package bioflix.booking.service;

import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Releases seats for bookings that were never paid.
 */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final Duration timeout;

    public BookingExpiryJob(BookingRepository bookingRepository, BookingService bookingService,
                            @Value("${app.booking.payment-timeout-minutes:15}") long timeoutMinutes) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.timeout = Duration.ofMinutes(timeoutMinutes);
    }

    @Scheduled(fixedDelay = 60_000)
    public void cancelExpiredBookings() {
        Instant cutoff = Instant.now().minus(timeout);
        for (Booking booking : bookingRepository.findByStatusAndCreatedAtBefore(BookingStatus.PENDING_PAYMENT, cutoff)) {
            log.info("Booking {} not paid within {} – cancelling", booking.getId(), timeout);
            bookingService.cancel(booking.getId(), "Payment not completed in time");
        }
    }
}
