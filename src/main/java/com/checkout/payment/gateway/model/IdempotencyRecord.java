package com.checkout.payment.gateway.model;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class IdempotencyRecord {

  private final String requestFingerprint;
  private final CompletableFuture<PostPaymentResponse> responseFuture;

  public IdempotencyRecord(String requestFingerprint) {
    this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
    this.responseFuture = new CompletableFuture<>();
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public CompletableFuture<PostPaymentResponse> getResponseFuture() {
    return responseFuture;
  }
}