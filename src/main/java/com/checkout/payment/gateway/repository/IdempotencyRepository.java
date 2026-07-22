package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.IdempotencyRecord;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

  private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

  public IdempotencyRecord putIfAbsent(String key, IdempotencyRecord record) {
    return records.putIfAbsent(key, record);
  }

  public void clear() {
    records.clear();
  }
}
