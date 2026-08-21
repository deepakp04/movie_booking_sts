package com.moviebooking.payment.dto;

import java.math.BigDecimal;

/**
 * Response containing Razorpay order details for client-side checkout.
 */
public record OrderResponse(
    String orderId,              // Razorpay order ID
    String transactionId,        // Our internal transaction UUID
    Long bookingId,
    BigDecimal amount,
    String currency,
    String keyId,                // Razorpay public key (test mode)
    LocalDateTime createdAt,
    LocalDateTime expiresAt
) {}
