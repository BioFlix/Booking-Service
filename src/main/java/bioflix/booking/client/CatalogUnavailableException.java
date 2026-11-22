package bioflix.booking.client;

/** Catalog could not be reached (down, too slow or circuit open) – returned to the client as 503. */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
