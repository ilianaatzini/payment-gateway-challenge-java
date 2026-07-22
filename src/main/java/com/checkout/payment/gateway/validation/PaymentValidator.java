package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class PaymentValidator {

  private static final String CARD_NUMBER_PATTERN = "\\d{14,19}";
  private static final String CVV_PATTERN = "\\d{3,4}";

  private static final Set<String> SUPPORTED_CURRENCIES = Set.of("GBP", "EUR", "USD");

  private final Clock clock;

  public PaymentValidator(Clock clock) {
    this.clock = clock;
  }

  public void validate(PostPaymentRequest request) {
    if (request == null) {
      throw new PaymentValidationException(List.of("payment request is required"));
    }

    List<String> errors = new ArrayList<>();

    validateCardNumber(request.getCardNumber(), errors);
    validateExpiry(request.getExpiryMonth(), request.getExpiryYear(), errors);
    validateCurrency(request.getCurrency(), errors);
    validateAmount(request.getAmount(), errors);
    validateCvv(request.getCvv(), errors);

    if (!errors.isEmpty()) {
      throw new PaymentValidationException(errors);
    }
  }

  private void validateCardNumber(String cardNumber, List<String> errors) {
    if (isBlank(cardNumber)) {
      errors.add("card_number is required");
      return;
    }

    if (!cardNumber.matches(CARD_NUMBER_PATTERN)) {
      errors.add("card_number must contain between 14 and 19 digits");
    }
  }

  private void validateExpiry(Integer expiryMonth, Integer expiryYear, List<String> errors) {
    boolean monthIsValid = validateExpiryMonth(expiryMonth, errors);

    boolean yearIsValid = validateExpiryYear(expiryYear, errors);

    if (!monthIsValid || !yearIsValid) {
      return;
    }

    YearMonth expiry = YearMonth.of(expiryYear, expiryMonth);
    YearMonth current = YearMonth.now(clock);

    if (expiry.isBefore(current)) {
      errors.add("card has expired");
    }
  }

  private boolean validateExpiryMonth(Integer expiryMonth, List<String> errors) {
    if (expiryMonth == null) {
      errors.add("expiry_month is required");
      return false;
    }

    if (expiryMonth < 1 || expiryMonth > 12) {
      errors.add("expiry_month must be between 1 and 12");
      return false;
    }

    return true;
  }

  private boolean validateExpiryYear(Integer expiryYear, List<String> errors) {
    if (expiryYear == null) {
      errors.add("expiry_year is required");
      return false;
    }

    if (expiryYear < 1000 || expiryYear > 9999) {
      errors.add("expiry_year must be a four-digit year");
      return false;
    }

    return true;
  }

  private void validateCurrency(String currency, List<String> errors) {
    if (isBlank(currency)) {
      errors.add("currency is required");
      return;
    }

    if (!currency.matches("[A-Za-z]{3}")) {
      errors.add("Currency must be a 3-letter code");
      return;
    }

    if (!SUPPORTED_CURRENCIES.contains(currency.toUpperCase(Locale.ROOT))) {
      errors.add("currency must be one of GBP, EUR, USD");
    }
  }

  private void validateAmount(Long amount, List<String> errors) {
    if (amount == null) {
      errors.add("amount is required");
      return;
    }

    if (amount <= 0) {
      errors.add("amount must be greater than zero");
    }
  }

  private void validateCvv(String cvv, List<String> errors) {
    if (isBlank(cvv)) {
      errors.add("cvv is required");
      return;
    }

    if (!cvv.matches(CVV_PATTERN)) {
      errors.add("cvv must contain 3 or 4 digits");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}