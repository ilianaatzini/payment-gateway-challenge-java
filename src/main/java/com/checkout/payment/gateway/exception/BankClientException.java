package com.checkout.payment.gateway.exception;

public class BankClientException extends RuntimeException {

  public BankClientException(String message) {
    super(message);
  }

  public BankClientException(String message, Throwable cause) {
    super(message, cause);
  }
}