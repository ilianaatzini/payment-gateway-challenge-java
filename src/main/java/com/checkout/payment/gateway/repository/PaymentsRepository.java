package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentsRepository {

  private final Map<UUID, PostPaymentResponse> payments = new ConcurrentHashMap<>();

  public PostPaymentResponse add(PostPaymentResponse payment) {
    payments.put(payment.getId(), payment);
    return payment;
  }

  public Optional<PostPaymentResponse> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

  public void clear() {
    payments.clear();
  }

}
