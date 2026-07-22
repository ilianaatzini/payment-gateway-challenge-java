package com.checkout.payment.gateway.exception;

public class IdempotencyRequestInProgressException extends RuntimeException {

  public IdempotencyRequestInProgressException(String message) {
    super(message);
  }
}