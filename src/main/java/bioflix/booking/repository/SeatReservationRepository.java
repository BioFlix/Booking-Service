package bioflix.booking.repository;

import bioflix.booking.entity.SeatReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SeatReservationRepository extends JpaRepository<SeatReservation, Long> {

    List<SeatReservation> findByShowtimeId(String showtimeId);

    List<SeatReservation> findByShowtimeIdAndSeatLabelIn(String showtimeId, Collection<String> seatLabels);

    @Modifying
    @Query("delete from SeatReservation s where s.bookingId = :bookingId")
    int deleteByBookingId(@Param("bookingId") Long bookingId);
}
