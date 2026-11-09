package bioflix.booking.repository;

import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, Instant before);
}
