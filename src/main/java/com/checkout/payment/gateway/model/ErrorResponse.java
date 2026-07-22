package com.checkout.payment.gateway.model;

import java.util.List;

public class ErrorResponse {
  private final String message;
  private final List<String> errors;

  public ErrorResponse(String message) {
    this(message, List.of());
  }

  public ErrorResponse(String message, List<String> errors) {
    this.message = message;
    this.errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public String getMessage() {
    return message;
  }

  public List<String> getErrors() {
    return errors;
  }

  @Override
  public String toString() {
    return "ErrorResponse{" +
        "message='" + message + '\'' +
        ", errors=" + errors +
        '}';
  }
}