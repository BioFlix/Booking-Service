package bioflix.booking.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A reservation of one or more seats for one showtime. State changes happen through the methods
 * below so the rules (which transitions are allowed, releasing seats) live in one place.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    private UUID id;

    /** Comes from the X-User-Id header (set by the API gateway). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "showtime_id", nullable = false, length = 24)
    private String showtimeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    /** Sum of the seat prices in øre. */
    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    // Snapshot of catalog data at booking time
    @Column(name = "movie_title", nullable = false)
    private String movieTitle;

    @Column(name = "hall_name", nullable = false, length = 100)
    private String hallName;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private CancelReason cancelReason;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Optimistic locking: the expiry job and the payment consumer can't both change the same booking. */
    @Version
    private Long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    @OrderBy("seatRow ASC, seatNumber ASC")
    private List<BookingSeat> seats = new ArrayList<>();

    protected Booking() {
        // for JPA
    }

    public Booking(UUID userId, String showtimeId, String movieTitle, String hallName, Instant startsAt,
                   String currency, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.showtimeId = showtimeId;
        this.movieTitle = movieTitle;
        this.hallName = hallName;
        this.startsAt = startsAt;
        this.currency = currency;
        this.expiresAt = expiresAt;
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    public void addSeat(String row, int number, String type, int price) {
        seats.add(new BookingSeat(this, showtimeId, row, number, type, price));
        totalAmount += price;
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

    // ------------------------------------------------------------ state changes

    public void markPaid(UUID paymentId, Instant when) {
        this.paymentId = paymentId;
        this.paidAt = when;
    }

    public void confirm(Instant when) {
        requireStatus(BookingStatus.PENDING_PAYMENT);
        status = BookingStatus.CONFIRMED;
        confirmedAt = when;
    }

    /** CANCELLED (customer, failed payment, cancelled showtime). Releases the seats. */
    public void cancel(CancelReason reason, Instant when) {
        if (!status.isActive()) {
            throw new IllegalStateException("Booking " + id + " is already " + status);
        }
        status = BookingStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = when;
        seats.forEach(BookingSeat::release);
    }

    /** EXPIRED: not paid within the hold time. Releases the seats. */
    public void expire(Instant when) {
        requireStatus(BookingStatus.PENDING_PAYMENT);
        status = BookingStatus.EXPIRED;
        cancelReason = CancelReason.EXPIRED;
        cancelledAt = when;
        seats.forEach(BookingSeat::release);
    }

    public boolean isPaid() {
        return paidAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    private void requireStatus(BookingStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Booking " + id + " is " + status + ", expected " + expected);
        }
    }

    // ------------------------------------------------------------ getters

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getShowtimeId() { return showtimeId; }
    public BookingStatus getStatus() { return status; }
    public int getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getMovieTitle() { return movieTitle; }
    public String getHallName() { return hallName; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public CancelReason getCancelReason() { return cancelReason; }
    public UUID getPaymentId() { return paymentId; }
    public Instant getPaidAt() { return paidAt; }
    public Long getVersion() { return version; }
    public List<BookingSeat> getSeats() { return Collections.unmodifiableList(seats); }
}
