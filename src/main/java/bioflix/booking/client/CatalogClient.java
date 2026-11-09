package bioflix.booking.client;

import bioflix.booking.exception.NotFoundException;
import bioflix.grpc.catalog.CatalogServiceGrpc;
import bioflix.grpc.catalog.GetShowtimeRequest;
import bioflix.grpc.catalog.ShowtimeDetails;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous gRPC call to catalog-service (the "gRPC" arrow in the diagram).
 */
@Component
public class CatalogClient {

    private final CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub;

    public CatalogClient(CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub) {
        this.catalogStub = catalogStub;
    }

    public ShowtimeInfo getShowtime(String showtimeId) {
        GetShowtimeRequest request = GetShowtimeRequest.newBuilder().setShowtimeId(showtimeId).build();
        try {
            ShowtimeDetails d = catalogStub
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getShowtime(request);

            return new ShowtimeInfo(
                    d.getShowtimeId(),
                    d.getMovieTitle(),
                    d.getHallName(),
                    Instant.ofEpochSecond(d.getStartTimeEpochSeconds()),
                    d.getPriceMinor(),
                    d.getCurrency(),
                    d.getRows(),
                    d.getSeatsPerRow());
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new NotFoundException("Showtime " + showtimeId + " not found");
            }
            throw new CatalogUnavailableException("Catalog service unavailable: " + ex.getStatus(), ex);
        }
    }
}
