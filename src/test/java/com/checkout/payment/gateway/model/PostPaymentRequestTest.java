package com.checkout.payment.gateway.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PostPaymentRequestTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldDeserializePaymentRequest() throws Exception {
    String json = """
        {
          "card_number": "2222405343248877",
          "expiry_month": 12,
          "expiry_year": 2028,
          "currency": "GBP",
          "amount": 1050,
          "cvv": "123"
        }
        """;

    PostPaymentRequest request = objectMapper.readValue(json, PostPaymentRequest.class);

    assertThat(request.getCardNumber()).isEqualTo("2222405343248877");
    assertThat(request.getExpiryMonth()).isEqualTo(12);
    assertThat(request.getExpiryYear()).isEqualTo(2028);
    assertThat(request.getCurrency()).isEqualTo("GBP");
    assertThat(request.getAmount()).isEqualTo(1050L);
    assertThat(request.getCvv()).isEqualTo("123");
  }

  @Test
  void shouldPreserveLeadingZeroInCvv() throws Exception {
    String json = """
        {
          "card_number": "2222405343248877",
          "expiry_month": 12,
          "expiry_year": 2028,
          "currency": "GBP",
          "amount": 1050,
          "cvv": "012"
        }
        """;

    PostPaymentRequest request = objectMapper.readValue(json, PostPaymentRequest.class);

    assertThat(request.getCvv()).isEqualTo("012");
  }
}