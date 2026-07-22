package com.checkout.payment.gateway.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.model.IdempotencyRecord;
import org.junit.jupiter.api.Test;

class IdempotencyRepositoryTest {

  private final IdempotencyRepository repository = new IdempotencyRepository();

  @Test
  void shouldKeepFirstRecordForAnIdempotencyKey() {
    IdempotencyRecord first = new IdempotencyRecord("first-fingerprint");
    IdempotencyRecord second = new IdempotencyRecord("second-fingerprint");

    IdempotencyRecord firstInsertResult = repository.putIfAbsent("same-key", first);
    IdempotencyRecord secondInsertResult = repository.putIfAbsent("same-key", second);

    assertThat(firstInsertResult).isNull();
    assertThat(secondInsertResult).isSameAs(first);
  }
}
