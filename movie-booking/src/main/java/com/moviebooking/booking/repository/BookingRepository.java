package com.moviebooking.booking.repository;

import com.moviebooking.booking.model.Booking;
import com.moviebooking.booking.model.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdAndUserId(Long id, Long userId);
    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, LocalDateTime time);
}
