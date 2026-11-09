package bioflix.booking.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_user", columnList = "user_id"),
        @Index(name = "idx_bookings_status", columnList = "status")
})
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Internal user id from user-service (comes from the X-User-Id header). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String showtimeId;

    // Snapshot of catalog data at booking time (so we don't need to call catalog again)
    private String movieTitle;
    private String hallName;
    private Instant startTime;

    /** Seat labels, e.g. ["C7", "C8"]. Kept as history even after cancellation. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"))
    @Column(name = "seat_label")
    private List<String> seats = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;

    /** Total in minor units (øre). */
    private long totalAmountMinor;

    private String currency;

    private String cancellationReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Booking() {
        // for JPA
    }

    public Booking(Long userId, String showtimeId, String movieTitle, String hallName, Instant startTime,
                   List<String> seats, long totalAmountMinor, String currency) {
        this.userId = userId;
        this.showtimeId = showtimeId;
        this.movieTitle = movieTitle;
        this.hallName = hallName;
        this.startTime = startTime;
        this.seats = new ArrayList<>(seats);
        this.totalAmountMinor = totalAmountMinor;
        this.currency = currency;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel(String reason) {
        this.status = BookingStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getShowtimeId() { return showtimeId; }
    public String getMovieTitle() { return movieTitle; }
    public String getHallName() { return hallName; }
    public Instant getStartTime() { return startTime; }
    public List<String> getSeats() { return seats; }
    public BookingStatus getStatus() { return status; }
    public long getTotalAmountMinor() { return totalAmountMinor; }
    public String getCurrency() { return currency; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
