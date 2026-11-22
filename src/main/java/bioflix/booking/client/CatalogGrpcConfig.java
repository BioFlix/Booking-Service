package bioflix.booking.client;

import bioflix.catalog.grpc.CatalogServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * Creates a CatalogServiceBlockingStub bean using channel "catalog" (see spring.grpc.client.channel.catalog).
 * Kept out of the main class so web-slice tests (@WebMvcTest) don't need a gRPC channel.
 */
@Configuration
@ImportGrpcClients(target = "catalog", types = CatalogServiceGrpc.CatalogServiceBlockingStub.class)
public class CatalogGrpcConfig {
}
