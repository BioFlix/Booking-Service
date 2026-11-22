package bioflix.booking.client;

import bioflix.booking.exception.NotFoundException;
import bioflix.catalog.grpc.CatalogServiceGrpc;
import bioflix.catalog.grpc.GetShowtimeRequest;
import bioflix.catalog.grpc.ShowtimeResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous gRPC call to catalog-service (the "gRPC" arrow in the diagram).
 * <ul>
 *   <li>Deadline of {@code app.catalog.timeout} (2 s) per call.</li>
 *   <li>Circuit breaker: if most of the recent calls failed, we stop calling Catalog for a while and answer 503 at once
 *       instead of letting every booking request wait for the timeout.</li>
 *   <li>NOT_FOUND / INVALID_ARGUMENT mean "no such showtime" (404). They are the client's fault, so they don't
 *       count as failures for the circuit breaker.</li>
 * </ul>
 */
@Component
public class CatalogClient {

    private final CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub;
    private final Duration timeout;
    private final CircuitBreaker circuitBreaker;

    public CatalogClient(CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub,
                         @Value("${app.catalog.timeout:2s}") Duration timeout) {
        this.catalogStub = catalogStub;
        this.timeout = timeout;
        this.circuitBreaker = CircuitBreaker.of("catalog", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(NotFoundException.class)
                .build());
    }

    public ShowtimeInfo getShowtime(String showtimeId) {
        try {
            return circuitBreaker.executeSupplier(() -> call(showtimeId));
        } catch (CallNotPermittedException ex) {
            throw new CatalogUnavailableException("Catalog service is unavailable (circuit open)", ex);
        }
    }

    CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private ShowtimeInfo call(String showtimeId) {
        GetShowtimeRequest request = GetShowtimeRequest.newBuilder().setShowtimeId(showtimeId).build();
        try {
            ShowtimeResponse r = catalogStub
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getShowtime(request);
            return toInfo(r);
        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND || code == Status.Code.INVALID_ARGUMENT) {
                throw new NotFoundException("Showtime " + showtimeId + " not found");
            }
            throw new CatalogUnavailableException("Catalog service unavailable: " + code, ex);
        }
    }

    static ShowtimeInfo toInfo(ShowtimeResponse r) {
        List<ShowtimeInfo.Row> rows = r.getRowsList().stream()
                .map(row -> new ShowtimeInfo.Row(row.getLabel(), row.getSeatsList().stream()
                        .map(seat -> new ShowtimeInfo.Seat(seat.getNumber(), seat.getType()))
                        .toList()))
                .toList();
        return new ShowtimeInfo(
                r.getShowtimeId(),
                r.getMovieTitle(),
                r.getHallName(),
                parseInstant(r.getStartsAt()),
                r.getStatus(),
                r.getCurrency().isBlank() ? "DKK" : r.getCurrency(),
                Map.copyOf(r.getPricesMap()),
                rows);
    }

    /** Catalog sends ISO-8601 UTC ("2026-12-01T19:00:00Z"); also accept an explicit offset. */
    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }
}
