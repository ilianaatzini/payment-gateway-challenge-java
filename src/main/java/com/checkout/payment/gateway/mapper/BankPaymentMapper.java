package com.checkout.payment.gateway.mapper;

import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class BankPaymentMapper {

  public BankPaymentRequest toBankRequest(PostPaymentRequest request) {
    return new BankPaymentRequest(
        request.getCardNumber(),
        formatExpiryDate(request.getExpiryMonth(), request.getExpiryYear()),
        request.getCurrency().toUpperCase(Locale.ROOT),
        request.getAmount(),
        request.getCvv()
    );
  }

  private String formatExpiryDate(Integer month, Integer year) {
    return "%02d/%04d".formatted(month, year);
  }
}