package bioflix.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Every minute: PENDING_PAYMENT bookings whose expires_at has passed become EXPIRED, their seats are released
 * and booking.cancelled (reason EXPIRED, wasPaid false) is published. Each booking is handled in its own
 * transaction, so one conflict doesn't stop the rest.
 */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingService bookingService;

    public BookingExpiryJob(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry-interval:60s}")
    public void expireBookings() {
        List<UUID> ids = bookingService.findExpiredPendingIds();
        for (UUID id : ids) {
            try {
                bookingService.expire(id);
            } catch (ConcurrencyFailureException ex) {
                // payment.completed changed the booking at the same moment – it wins, we look again next minute
                log.info("Booking {} changed while expiring it – skipped this round", id);
            } catch (RuntimeException ex) {
                log.error("Could not expire booking {}", id, ex);
            }
        }
    }
}
