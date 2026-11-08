package com.ticketflow.paymentservice.repos;

import com.ticketflow.paymentservice.entities.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepo extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    List<Payment> findAllByOrderByCreatedAtDescIdDesc();
}
