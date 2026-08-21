package com.moviebooking.payment.service;

import com.moviebooking.booking.model.Booking;
import com.moviebooking.booking.model.BookingStatus;
import com.moviebooking.booking.repository.BookingRepository;
import com.moviebooking.common.exception.BusinessException;
import com.moviebooking.common.exception.ResourceNotFoundException;
import com.moviebooking.payment.dto.CreateOrderRequest;
import com.moviebooking.payment.dto.OrderResponse;
import com.moviebooking.payment.dto.PaymentResponse;
import com.moviebooking.payment.dto.VerifyPaymentRequest;
import com.moviebooking.payment.model.PaymentTransaction;
import com.moviebooking.payment.repository.PaymentTransactionRepository;
import com.moviebooking.stream.dto.SeatUpdateEvent;
import com.moviebooking.stream.service.SeatStreamService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String CURRENCY = "INR";

    @Value("${razorpay.key.id:rzp_test_XXXXXXXXXX}")
    private String razorpayKeyId;

    @Value("${razorpay.key.secret:XXXXXXXXXX}")
    private String razorpayKeySecret;

    private final PaymentTransactionRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final SeatStreamService seatStreamService;

    public PaymentService(PaymentTransactionRepository paymentRepository,
                         BookingRepository bookingRepository,
                         SeatStreamService seatStreamService) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.seatStreamService = seatStreamService;
    }

    /**
     * Create a Razorpay order for a booking.
     * Validates that the booking hold is still active before allowing payment.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest req) {
        Booking booking = bookingRepository.findById(req.bookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + req.bookingId()));

        // Validate booking status
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessException("This booking is not pending payment. Status: " + booking.getStatus());
        }

        // Check if hold has expired
        LocalDateTime now = LocalDateTime.now();
        if (booking.getHoldExpiresAt() != null && now.isAfter(booking.getHoldExpiresAt())) {
            // Expire the booking and release seats
            booking.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(booking);
            
            // Broadcast seat releases
            String[] seatCodes = booking.getSeatCodes().split(",");
            for (String code : seatCodes) {
                seatStreamService.broadcastSeatUpdate(
                    booking.getShow().getId(),
                    new SeatUpdateEvent(booking.getShow().getId(), code, "AVAILABLE", null, "EXPIRED")
                );
            }
            
            throw new BusinessException("Session expired. Seats have been released. Please try booking again.");
        }

        // Check if payment transaction already exists
        PaymentTransaction existing = paymentRepository.findByBookingId(req.bookingId())
            .orElse(null);
        
        if (existing != null) {
            if (existing.getStatus() == PaymentTransaction.PaymentStatus.SUCCESS) {
                throw new BusinessException("Payment already completed for this booking.");
            }
            if (existing.getStatus() == PaymentTransaction.PaymentStatus.EXPIRED) {
                throw new BusinessException("Payment order expired. Please create a new order.");
            }
            // Return existing pending order
            return toOrderResponse(existing);
        }

        // Create new payment transaction
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTransactionId(booking.getTransactionId());
        transaction.setBookingId(booking.getId());
        transaction.setAmount(booking.getTotalAmount());
        transaction.setCurrency(CURRENCY);
        transaction.setStatus(PaymentTransaction.PaymentStatus.PENDING);
        
        // Generate Razorpay order ID (in real implementation, call Razorpay API here)
        String razorpayOrderId = "order_" + System.currentTimeMillis() + "_" + booking.getId();
        transaction.setRazorpayOrderId(razorpayOrderId);
        
        PaymentTransaction saved = paymentRepository.save(transaction);
        
        log.info("Created payment order {} for booking {}", razorpayOrderId, booking.getId());
        
        return toOrderResponse(saved);
    }

    /**
     * Verify payment signature and confirm booking.
     * Uses HMAC-SHA256 to verify Razorpay's signature.
     */
    @Transactional
    public PaymentResponse verifyPayment(VerifyPaymentRequest req) {
        PaymentTransaction transaction = paymentRepository.findByRazorpayOrderIdForUpdate(req.razorpayOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + req.razorpayOrderId()));

        // Verify signature
        boolean isValid = verifySignature(
            req.razorpayOrderId(),
            req.razorpayPaymentId(),
            req.razorpaySignature()
        );

        if (!isValid) {
            transaction.setStatus(PaymentTransaction.PaymentStatus.FAILED);
            transaction.setFailureReason("Invalid signature");
            paymentRepository.save(transaction);
            throw new BusinessException("Payment verification failed. Invalid signature.");
        }

        // Update transaction
        transaction.setRazorpayPaymentId(req.razorpayPaymentId());
        transaction.setRazorpaySignature(req.razorpaySignature());
        transaction.setStatus(PaymentTransaction.PaymentStatus.SUCCESS);
        transaction.setPaidAt(LocalDateTime.now());
        paymentRepository.save(transaction);

        // Confirm booking
        Booking booking = bookingRepository.findById(transaction.getBookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        log.info("Payment verified successfully. Booking {} confirmed.", booking.getId());

        return new PaymentResponse(
            true,
            "Payment successful",
            transaction.getTransactionId(),
            req.razorpayPaymentId(),
            booking.getId(),
            booking.getTotalAmount(),
            transaction.getPaidAt()
        );
    }

    /**
     * Handle Razorpay webhook callback.
     * Idempotent - safe to call multiple times.
     */
    @Transactional
    public void handleWebhook(String orderId, String paymentId, String signature, String event) {
        log.info("Received webhook for order {} event {}", orderId, event);
        
        PaymentTransaction transaction = paymentRepository.findByRazorpayOrderIdForUpdate(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        // Prevent double-processing
        if (transaction.getStatus() == PaymentTransaction.PaymentStatus.SUCCESS) {
            log.warn("Payment already confirmed for order {}", orderId);
            return;
        }

        // Verify signature
        boolean isValid = verifySignature(orderId, paymentId, signature);
        if (!isValid) {
            log.error("Webhook signature verification failed for order {}", orderId);
            return;
        }

        // Process based on event type
        if ("payment.captured".equals(event)) {
            transaction.setRazorpayPaymentId(paymentId);
            transaction.setRazorpaySignature(signature);
            transaction.setStatus(PaymentTransaction.PaymentStatus.SUCCESS);
            transaction.setPaidAt(LocalDateTime.now());
            paymentRepository.save(transaction);

            // Confirm booking
            Booking booking = bookingRepository.findById(transaction.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
            
            if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
                booking.setStatus(BookingStatus.CONFIRMED);
                bookingRepository.save(booking);
                log.info("Booking {} confirmed via webhook", booking.getId());
            }
        } else if ("payment.failed".equals(event)) {
            transaction.setStatus(PaymentTransaction.PaymentStatus.FAILED);
            transaction.setFailureReason("Payment failed via webhook");
            paymentRepository.save(transaction);
            
            // Release seats
            expireBooking(transaction.getBookingId());
        }
    }

    /**
     * Verify HMAC-SHA256 signature from Razorpay.
     */
    private boolean verifySignature(String orderId, String paymentId, String signature) {
        try {
            String data = orderId + "|" + paymentId;
            
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(
                razorpayKeySecret.getBytes(StandardCharsets.UTF_8), 
                "HmacSHA256"
            );
            sha256_HMAC.init(secret_key);
            
            byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = HexFormat.of().formatHex(hash);
            
            return expectedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }

    /**
     * Expire a booking and release seats.
     */
    private void expireBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElse(null);
        
        if (booking != null && booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            booking.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(booking);
            
            // Broadcast seat releases
            String[] seatCodes = booking.getSeatCodes().split(",");
            for (String code : seatCodes) {
                seatStreamService.broadcastSeatUpdate(
                    booking.getShow().getId(),
                    new SeatUpdateEvent(booking.getShow().getId(), code, "AVAILABLE", null, "EXPIRED")
                );
            }
            
            log.info("Booking {} expired, seats released", bookingId);
        }
    }

    private OrderResponse toOrderResponse(PaymentTransaction t) {
        return new OrderResponse(
            t.getRazorpayOrderId(),
            t.getTransactionId(),
            t.getBookingId(),
            t.getAmount(),
            t.getCurrency(),
            razorpayKeyId,
            t.getCreatedAt(),
            t.getExpiresAt()
        );
    }
}
