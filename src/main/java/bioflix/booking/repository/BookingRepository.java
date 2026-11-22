package bioflix.booking.repository;

import bioflix.booking.entity.Booking;
import bioflix.booking.entity.BookingStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @EntityGraph(attributePaths = "seats")
    Optional<Booking> findWithSeatsById(UUID id);

    @EntityGraph(attributePaths = "seats")
    List<Booking> findByUserIdOrderByCreatedAtDesc(UUID userId);

    // No entity graph here: a fetch join together with a limit would be paged in memory.
    // The seats are loaded lazily inside the service transaction instead.
    List<Booking> findByStatusOrderByCreatedAtDesc(BookingStatus status, Limit limit);

    List<Booking> findAllByOrderByCreatedAtDesc(Limit limit);

    /** Ids only – the expiry job handles each booking in its own transaction. */
    @Query("select b.id from Booking b where b.status = :status and b.expiresAt < :now order by b.expiresAt")
    List<UUID> findIdsByStatusAndExpiresAtBefore(@Param("status") BookingStatus status, @Param("now") Instant now);
}
