package bioflix.booking.service;

/**
 * Seat labels are "row letter + seat number", e.g. "A1", "C7", "J14".
 */
final class SeatLabels {

    private SeatLabels() {
    }

    static boolean isValid(String label, int rows, int seatsPerRow) {
        if (label == null || label.length() < 2) {
            return false;
        }
        int rowIndex = label.charAt(0) - 'A';           // A -> 0, B -> 1 ...
        int seatNumber;
        try {
            seatNumber = Integer.parseInt(label.substring(1));
        } catch (NumberFormatException e) {
            return false;
        }
        return rowIndex >= 0 && rowIndex < rows && seatNumber >= 1 && seatNumber <= seatsPerRow;
    }
}
