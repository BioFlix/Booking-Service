package bioflix.booking.client;

import bioflix.booking.exception.NotFoundException;
import bioflix.catalog.grpc.CatalogServiceGrpc;
import bioflix.catalog.grpc.GetShowtimeRequest;
import bioflix.catalog.grpc.Seat;
import bioflix.catalog.grpc.SeatRow;
import bioflix.catalog.grpc.ShowtimeResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a fake Catalog gRPC server in the same JVM (no network, no Docker). */
class CatalogClientTest {

    private static final String SHOW = "66f000000000000000000c01";

    /** What the fake server does for the next call. */
    private final AtomicReference<Behaviour> behaviour = new AtomicReference<>(Behaviour.OK);

    private Server server;
    private ManagedChannel channel;
    private CatalogClient client;

    enum Behaviour { OK, NOT_FOUND, INVALID, UNAVAILABLE, SLOW }

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new FakeCatalog()).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        client = new CatalogClient(CatalogServiceGrpc.newBlockingStub(channel), Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void mapsTheNewContract() {
        ShowtimeInfo info = client.getShowtime(SHOW);

        assertThat(info.showtimeId()).isEqualTo(SHOW);
        assertThat(info.movieTitle()).isEqualTo("Dune: Part Two");
        assertThat(info.hallName()).isEqualTo("Sal 1");
        assertThat(info.startsAt()).isEqualTo(Instant.parse("2030-01-01T19:00:00Z"));
        assertThat(info.currency()).isEqualTo("DKK");
        assertThat(info.seatType("F", 7)).contains("STANDARD");
        assertThat(info.seatType("f", 8)).contains("VIP");
        assertThat(info.seatType("F", 99)).isEmpty();
        assertThat(info.seatType("Z", 1)).isEmpty();
        assertThat(info.priceFor("STANDARD")).contains(13500);
        assertThat(info.priceFor("VIP")).contains(17500);
        assertThat(info.priceFor("WHEELCHAIR")).isEmpty();
        assertThat(info.isCancelled()).isFalse();
    }

    @Test
    void notFoundAndInvalidIdBecome404() {
        behaviour.set(Behaviour.NOT_FOUND);
        assertThatThrownBy(() -> client.getShowtime(SHOW)).isInstanceOf(NotFoundException.class);

        behaviour.set(Behaviour.INVALID);
        assertThatThrownBy(() -> client.getShowtime("not-an-object-id")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unavailableAndTimeoutBecome503() {
        behaviour.set(Behaviour.UNAVAILABLE);
        assertThatThrownBy(() -> client.getShowtime(SHOW)).isInstanceOf(CatalogUnavailableException.class);

        behaviour.set(Behaviour.SLOW);
        assertThatThrownBy(() -> client.getShowtime(SHOW)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresButNotForNotFound() {
        behaviour.set(Behaviour.NOT_FOUND);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.getShowtime(SHOW)).isInstanceOf(NotFoundException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);

        behaviour.set(Behaviour.UNAVAILABLE);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.getShowtime(SHOW)).isInstanceOf(CatalogUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        // while open, even a healthy catalog isn't called – we answer 503 at once
        behaviour.set(Behaviour.OK);
        assertThatThrownBy(() -> client.getShowtime(SHOW))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasMessageContaining("circuit open");
    }

    private class FakeCatalog extends CatalogServiceGrpc.CatalogServiceImplBase {
        @Override
        public void getShowtime(GetShowtimeRequest request, StreamObserver<ShowtimeResponse> observer) {
            switch (behaviour.get()) {
                case NOT_FOUND -> observer.onError(Status.NOT_FOUND.asRuntimeException());
                case INVALID -> observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
                case UNAVAILABLE -> observer.onError(Status.UNAVAILABLE.asRuntimeException());
                case SLOW -> {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    observer.onNext(showtime());
                    observer.onCompleted();
                }
                case OK -> {
                    observer.onNext(showtime());
                    observer.onCompleted();
                }
            }
        }
    }

    static ShowtimeResponse showtime() {
        return ShowtimeResponse.newBuilder()
                .setShowtimeId(SHOW)
                .setMovieId("m1")
                .setMovieTitle("Dune: Part Two")
                .setHallId("h1")
                .setHallName("Sal 1")
                .setCinemaName("BioFlix City")
                .setStartsAt("2030-01-01T19:00:00Z")
                .setEndsAt("2030-01-01T21:46:00Z")
                .setFormat("TWO_D")
                .setStatus("SCHEDULED")
                .setCurrency("DKK")
                .putPrices("STANDARD", 13500)
                .putPrices("VIP", 17500)
                .addRows(SeatRow.newBuilder().setLabel("F")
                        .addSeats(Seat.newBuilder().setNumber(7).setType("STANDARD"))
                        .addSeats(Seat.newBuilder().setNumber(8).setType("VIP")))
                .build();
    }
}
