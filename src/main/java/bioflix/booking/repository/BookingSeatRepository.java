package bioflix.booking.repository;

import bioflix.booking.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

    /** Seats that are held right now (pending or confirmed bookings) for a showtime. */
    @Query("select s from BookingSeat s where s.showtimeId = :showtimeId and s.released = false")
    List<BookingSeat> findTaken(@Param("showtimeId") String showtimeId);
}
