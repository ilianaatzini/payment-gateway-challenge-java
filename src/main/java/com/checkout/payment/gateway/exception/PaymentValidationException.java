package com.checkout.payment.gateway.exception;

import java.util.List;

public class PaymentValidationException extends RuntimeException {

  private final List<String> errors;

  public PaymentValidationException(List<String> errors) {
    super("Invalid payment request");
    this.errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public List<String> getErrors() {
    return errors;
  }
}