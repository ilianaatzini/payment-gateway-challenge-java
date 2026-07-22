package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PaymentRequestFingerprint {

  public String create(PostPaymentRequest request) {
    String canonicalRequest = String.join(
        "|",
        request.getCardNumber(),
        String.valueOf(request.getExpiryMonth()),
        String.valueOf(request.getExpiryYear()),
        request.getCurrency().toUpperCase(Locale.ROOT),
        String.valueOf(request.getAmount()),
        request.getCvv()
    );

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}