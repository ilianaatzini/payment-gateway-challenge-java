package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.Test;

class PaymentRequestFingerprintTest {

  private final PaymentRequestFingerprint fingerprint = new PaymentRequestFingerprint();

  @Test
  void shouldProduceSameFingerprintForEquivalentRequests() {
    PostPaymentRequest first = validRequest();
    PostPaymentRequest second = validRequest();
    second.setCurrency("gbp");

    assertThat(fingerprint.create(second)).isEqualTo(fingerprint.create(first));
  }

  @Test
  void shouldProduceDifferentFingerprintWhenAmountChanges() {
    PostPaymentRequest first = validRequest();
    PostPaymentRequest second = validRequest();
    second.setAmount(2000L);

    assertThat(fingerprint.create(second)).isNotEqualTo(fingerprint.create(first));
  }

  @Test
  void shouldProduceDifferentFingerprintWhenCardNumberChanges() {
    PostPaymentRequest first = validRequest();
    PostPaymentRequest second = validRequest();
    second.setCardNumber("5555555555554444");

    assertThat(fingerprint.create(second)).isNotEqualTo(fingerprint.create(first));
  }

  @Test
  void shouldProduceDifferentFingerprintWhenCvvChanges() {
    PostPaymentRequest first = validRequest();
    PostPaymentRequest second = validRequest();

    second.setCvv("999");

    assertThat(fingerprint.create(second))
        .isNotEqualTo(fingerprint.create(first));
  }

  private PostPaymentRequest validRequest() {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber("2222405343248877");
    request.setExpiryMonth(12);
    request.setExpiryYear(2027);
    request.setCurrency("GBP");
    request.setAmount(1050L);
    request.setCvv("123");
    return request;
  }
}