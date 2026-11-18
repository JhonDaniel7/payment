package com.microservices.payment.repository;

import com.microservices.payment.model.entity.Payment;
import com.microservices.payment.model.enums.PaymentGateway;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Page<Payment> findByUserId(UUID userId, Pageable pageable);

    List<Payment> findByOrderId(UUID orderId);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    Page<Payment> findByUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);

    Page<Payment> findByPaymentRegion(PaymentRegion paymentRegion, Pageable pageable);

    Page<Payment> findByPaymentMethod(PaymentMethod paymentMethod, Pageable pageable);

    boolean existsByPaymentReference(String paymentReference);

    Optional<Payment> findByTransactionId(String transactionId);

    Optional<Payment> findFirstByOrderIdAndPaymentGatewayOrderByCreatedAtDesc(UUID orderId, PaymentGateway paymentGateway);

    long countByOrderIdAndPaymentGateway(UUID orderId, PaymentGateway paymentGateway);
}
