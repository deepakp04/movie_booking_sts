package com.moviebooking.stream.dto;

/**
 * DTO for Server-Sent Events (SSE) seat updates.
 * Pushed to clients when a seat's status changes (HELD, BOOKED, AVAILABLE).
 */
public record SeatUpdateEvent(
    Long showId,
    String seatCode,
    String status,          // AVAILABLE, HELD, BOOKED
    Long heldByUserId,      // null unless HELD
    String reason           // "HELD", "BOOKED", "RELEASED", "EXPIRED"
) {}
