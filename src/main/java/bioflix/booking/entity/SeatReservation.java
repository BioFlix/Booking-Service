package bioflix.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * "Lock" table: one row per taken seat per showtime.
 * The unique constraint makes the database reject double bookings, even when two
 * requests arrive at the same time. Rows are deleted when a booking is cancelled.
 */
@Entity
@Table(name = "seat_reservations",
        uniqueConstraints = @UniqueConstraint(name = "uk_showtime_seat", columnNames = {"showtime_id", "seat_label"}))
public class SeatReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "showtime_id", nullable = false)
    private String showtimeId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    protected SeatReservation() {
        // for JPA
    }

    public SeatReservation(String showtimeId, String seatLabel, Long bookingId) {
        this.showtimeId = showtimeId;
        this.seatLabel = seatLabel;
        this.bookingId = bookingId;
    }

    public Long getId() { return id; }
    public String getShowtimeId() { return showtimeId; }
    public String getSeatLabel() { return seatLabel; }
    public Long getBookingId() { return bookingId; }
}
