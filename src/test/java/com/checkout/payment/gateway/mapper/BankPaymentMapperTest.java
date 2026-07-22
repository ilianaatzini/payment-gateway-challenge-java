package com.checkout.payment.gateway.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.Test;

class BankPaymentMapperTest {

  private final BankPaymentMapper mapper = new BankPaymentMapper();

  @Test
  void shouldMapPaymentRequestToBankRequest() {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber("2222405343248877");
    request.setExpiryMonth(7);
    request.setExpiryYear(2027);
    request.setCurrency("gbp");
    request.setAmount(1050L);
    request.setCvv("123");

    BankPaymentRequest result = mapper.toBankRequest(request);

    assertThat(result.cardNumber()).isEqualTo("2222405343248877");
    assertThat(result.expiryDate()).isEqualTo("07/2027");
    assertThat(result.currency()).isEqualTo("GBP");
    assertThat(result.amount()).isEqualTo(1050L);
    assertThat(result.cvv()).isEqualTo("123");
  }
}
