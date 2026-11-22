package bioflix.booking.controller;

import bioflix.booking.client.CatalogUnavailableException;
import bioflix.booking.dto.BookingResponse;
import bioflix.booking.entity.BookingStatus;
import bioflix.booking.exception.ConflictException;
import bioflix.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String SHOW = "66f000000000000000000c01";
    private static final String VALID_BODY = """
            { "showtimeId": "%s", "seats": [ { "row": "F", "number": 7 } ] }
            """.formatted(SHOW);

    @Autowired MockMvc mvc;
    @MockitoBean BookingService bookingService;

    @Test
    void createReturns201WithTheBooking() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookingService.createBooking(eq(ALICE), any())).thenReturn(booking(id));

        mvc.perform(post("/api/bookings").header("X-User-Id", ALICE.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(13500))
                .andExpect(jsonPath("$.seats[0].row").value("F"))
                .andExpect(jsonPath("$.seats[0].type").value("STANDARD"));
    }

    @Test
    void missingUserHeaderIs401() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(bookingService);
    }

    @Test
    void userIdMustBeAUuid() throws Exception {
        mvc.perform(post("/api/bookings").header("X-User-Id", "42")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidSeatsAre400() throws Exception {
        String badRow = """
                { "showtimeId": "%s", "seats": [ { "row": "7", "number": 7 } ] }""".formatted(SHOW);
        String noSeats = """
                { "showtimeId": "%s", "seats": [] }""".formatted(SHOW);
        String zero = """
                { "showtimeId": "%s", "seats": [ { "row": "F", "number": 0 } ] }""".formatted(SHOW);

        for (String body : List.of(badRow, noSeats, zero)) {
            mvc.perform(post("/api/bookings").header("X-User-Id", ALICE.toString())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(bookingService);
    }

    @Test
    void seatTakenIs409AndCatalogDownIs503() throws Exception {
        when(bookingService.createBooking(eq(ALICE), any()))
                .thenThrow(new ConflictException("Seats already taken: [F7]"))
                .thenThrow(new CatalogUnavailableException("down", null));

        mvc.perform(post("/api/bookings").header("X-User-Id", ALICE.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/bookings").header("X-User-Id", ALICE.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void adminListNeedsAdminRole() throws Exception {
        mvc.perform(get("/api/bookings")).andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings").header("X-User-Role", "CUSTOMER")).andExpect(status().isForbidden());

        when(bookingService.list(null, 100)).thenReturn(List.of());
        mvc.perform(get("/api/bookings").header("X-User-Role", "ADMIN")).andExpect(status().isOk());
    }

    private static BookingResponse booking(UUID id) {
        return new BookingResponse(id, ALICE, SHOW, "Dune: Part Two", "Sal 1", Instant.parse("2030-01-01T19:00:00Z"),
                BookingStatus.PENDING_PAYMENT, List.of(new BookingResponse.SeatResponse("F", 7, "STANDARD", 13500)),
                13500, "DKK", Instant.parse("2029-12-31T12:15:00Z"), Instant.parse("2029-12-31T12:00:00Z"),
                null, null, null, false);
    }
}
