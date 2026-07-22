package com.checkout.payment.gateway.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentValidatorTest {

  private PaymentValidator validator;

  @BeforeEach
  void setUp() {
    Clock fixedClock =
        Clock.fixed(
            Instant.parse("2026-07-18T00:00:00Z"),
            ZoneOffset.UTC
        );

    validator = new PaymentValidator(fixedClock);
  }

  @Test
  void shouldAcceptValidPaymentRequest() {
    PostPaymentRequest request = validRequest();

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  // Card number

  @Test
  void shouldRejectMissingCardNumber() {
    PostPaymentRequest request = validRequest();
    request.setCardNumber(null);

    assertValidationError(request, "card_number is required");
  }

  @Test
  void shouldRejectBlankCardNumber() {
    PostPaymentRequest request = validRequest();
    request.setCardNumber(" ");

    assertValidationError(request, "card_number is required");
  }

  @Test
  void shouldRejectNonNumericCardNumber() {
    PostPaymentRequest request = validRequest();
    request.setCardNumber("222240534324887A");

    assertValidationError(
        request,
        "card_number must contain between 14 and 19 digits"
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "1234567890123",
      "12345678901234567890"
  })
  void shouldRejectCardNumberOutsideAllowedLength(String cardNumber) {
    PostPaymentRequest request = validRequest();
    request.setCardNumber(cardNumber);

    assertValidationError(request, "card_number must contain between 14 and 19 digits");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "12345678901234",
      "1234567890123456789"
  })
  void shouldAcceptCardNumberLengthBoundaries(String cardNumber) {
    PostPaymentRequest request = validRequest();
    request.setCardNumber(cardNumber);

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  // Expiry

  @Test
  void shouldRejectMissingExpiryMonth() {
    PostPaymentRequest request = validRequest();
    request.setExpiryMonth(null);

    assertValidationError(request, "expiry_month is required");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 13})
  void shouldRejectExpiryMonthOutsideValidRange(int expiryMonth) {
    PostPaymentRequest request = validRequest();
    request.setExpiryMonth(expiryMonth);

    assertValidationError(request, "expiry_month must be between 1 and 12");
  }

  @Test
  void shouldRejectMissingExpiryYear() {
    PostPaymentRequest request = validRequest();
    request.setExpiryYear(null);

    assertValidationError(request, "expiry_year is required");
  }

  @Test
  void shouldRejectCardExpiredInPreviousMonth() {
    PostPaymentRequest request = validRequest();
    request.setExpiryMonth(6);
    request.setExpiryYear(2026);

    assertValidationError(request, "card has expired");
  }

  @Test
  void shouldRejectCardExpiredInPreviousYear() {
    PostPaymentRequest request = validRequest();
    request.setExpiryMonth(12);
    request.setExpiryYear(2025);

    assertValidationError(request, "card has expired");
  }

  @Test
  void shouldAcceptCardExpiringInCurrentMonth() {
    PostPaymentRequest request = validRequest();
    request.setExpiryMonth(7);
    request.setExpiryYear(2026);

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  @Test
  void shouldAcceptCardExpiringInFutureMonth() {
    PostPaymentRequest request = validRequest();
    request.setExpiryMonth(8);
    request.setExpiryYear(2026);

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  // Currency

  @Test
  void shouldRejectMissingCurrency() {
    PostPaymentRequest request = validRequest();
    request.setCurrency(null);

    assertValidationError(request, "currency is required");
  }

  @Test
  void shouldRejectBlankCurrency() {
    PostPaymentRequest request = validRequest();
    request.setCurrency(" ");

    assertValidationError(request, "currency is required");
  }

  @Test
  void shouldRejectUnsupportedCurrency() {
    PostPaymentRequest request = validRequest();
    request.setCurrency("JPY");

    assertValidationError(request, "currency must be one of GBP, EUR, USD");
  }

  @ParameterizedTest
  @ValueSource(strings = {"GBP", "EUR", "USD", "gbp"})
  void shouldAcceptSupportedCurrency(String currency) {
    PostPaymentRequest request = validRequest();
    request.setCurrency(currency);

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  // Amount

  @Test
  void shouldRejectMissingAmount() {
    PostPaymentRequest request = validRequest();
    request.setAmount(null);

    assertValidationError(request, "amount is required");
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, -100L})
  void shouldRejectAmountThatIsNotPositive(long amount) {
    PostPaymentRequest request = validRequest();
    request.setAmount(amount);

    assertValidationError(request, "amount must be greater than zero");
  }

  @Test
  void shouldAcceptPositiveAmount() {
    PostPaymentRequest request = validRequest();
    request.setAmount(1L);

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  // CVV

  @Test
  void shouldRejectMissingCvv() {
    PostPaymentRequest request = validRequest();
    request.setCvv(null);

    assertValidationError(request, "cvv is required");
  }

  @Test
  void shouldRejectBlankCvv() {
    PostPaymentRequest request = validRequest();
    request.setCvv(" ");

    assertValidationError(request, "cvv is required");
  }

  @Test
  void shouldRejectNonNumericCvv() {
    PostPaymentRequest request = validRequest();
    request.setCvv("12A");

    assertValidationError(request, "cvv must contain 3 or 4 digits");
  }

  @ParameterizedTest
  @ValueSource(strings = {"12", "12345"})
  void shouldRejectCvvOutsideAllowedLength(String cvv) {
    PostPaymentRequest request = validRequest();
    request.setCvv(cvv);

    assertValidationError(request, "cvv must contain 3 or 4 digits");
  }

  @ParameterizedTest
  @ValueSource(strings = {"012", "1234"})
  void shouldAcceptValidCvv(String cvv) {
    PostPaymentRequest request = validRequest();
    request.setCvv(cvv);

    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  // Error aggregation

  @Test
  void shouldRejectNullRequest() {
    assertThatThrownBy(() -> validator.validate(null))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(exception -> {
          PaymentValidationException validationException = (PaymentValidationException) exception;

          assertThat(validationException.getErrors()).containsExactly("payment request is required");
        });
  }

  @Test
  void shouldReturnAllValidationErrors() {
    PostPaymentRequest request = new PostPaymentRequest();

    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(exception -> {
          PaymentValidationException validationException =
              (PaymentValidationException) exception;

          assertThat(validationException.getErrors())
              .containsExactlyInAnyOrder(
                  "card_number is required",
                  "expiry_month is required",
                  "expiry_year is required",
                  "currency is required",
                  "amount is required",
                  "cvv is required"
              );
        });
  }

  private void assertValidationError(PostPaymentRequest request, String expectedError) {
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(exception -> {
          PaymentValidationException validationException =
              (PaymentValidationException) exception;

          assertThat(validationException.getErrors()).contains(expectedError);
        });
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