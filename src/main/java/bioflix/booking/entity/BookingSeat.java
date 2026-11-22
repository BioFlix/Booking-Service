package bioflix.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One seat in a booking. While {@code released} is false the partial unique index
 * uq_booking_seats_active (showtime_id, seat_row, seat_number) WHERE NOT released
 * makes the database reject a second booking of the same seat – even if two requests arrive at once.
 * Rows are kept as history when a booking is cancelled or expires; they are only marked released.
 */
@Entity
@Table(name = "booking_seats")
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "showtime_id", nullable = false, length = 24)
    private String showtimeId;

    @Column(name = "seat_row", nullable = false, length = 2)
    private String seatRow;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(name = "seat_type", nullable = false, length = 20)
    private String seatType;

    /** Price in øre. */
    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private boolean released;

    protected BookingSeat() {
        // for JPA
    }

    BookingSeat(Booking booking, String showtimeId, String seatRow, int seatNumber, String seatType, int price) {
        this.booking = booking;
        this.showtimeId = showtimeId;
        this.seatRow = seatRow;
        this.seatNumber = seatNumber;
        this.seatType = seatType;
        this.price = price;
    }

    void release() {
        released = true;
    }

    public Long getId() { return id; }
    public Booking getBooking() { return booking; }
    public String getShowtimeId() { return showtimeId; }
    public String getSeatRow() { return seatRow; }
    public int getSeatNumber() { return seatNumber; }
    public String getSeatType() { return seatType; }
    public int getPrice() { return price; }
    public boolean isReleased() { return released; }
}
