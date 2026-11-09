package bioflix.booking.client;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Catalog could not be reached – returned to the client as 503. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
