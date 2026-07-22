package com.checkout.payment.gateway.enums;

public enum PaymentFailureReason {
  VALIDATION_ERROR,
  BANK_REJECTED_REQUEST,
  BANK_UNAVAILABLE,
  UNEXPECTED_ERROR
}
