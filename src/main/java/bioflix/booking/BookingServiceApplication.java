package bioflix.booking;

import bioflix.grpc.catalog.CatalogServiceGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// Creates a CatalogServiceBlockingStub bean using channel "catalog" (see application.yml)
@ImportGrpcClients(target = "catalog", types = CatalogServiceGrpc.CatalogServiceBlockingStub.class)
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
