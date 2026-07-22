package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankSimulatorClient;
import com.checkout.payment.gateway.enums.PaymentFailureReason;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankClientException;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.IdempotencyRequestInProgressException;
import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.mapper.BankPaymentMapper;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.IdempotencyRecord;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.IdempotencyRepository;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.validation.PaymentValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
  private static final String BANK_REJECTED_REQUEST = "BANK_REJECTED_REQUEST";
  private static final String BANK_UNAVAILABLE = "BANK_UNAVAILABLE";
  private static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";
  private static final long IDEMPOTENCY_WAIT_SECONDS = 30L;

  private final PaymentsRepository paymentsRepository;
  private final IdempotencyRepository idempotencyRepository;
  private final PaymentValidator paymentValidator;
  private final BankPaymentMapper bankPaymentMapper;
  private final BankSimulatorClient bankSimulatorClient;
  private final PaymentRequestFingerprint paymentRequestFingerprint;
  private final MeterRegistry meterRegistry;
  private final Timer paymentProcessingTimer;

  public PaymentGatewayService(
      PaymentsRepository paymentsRepository,
      IdempotencyRepository idempotencyRepository,
      PaymentValidator paymentValidator,
      BankPaymentMapper bankPaymentMapper,
      BankSimulatorClient bankSimulatorClient,
      PaymentRequestFingerprint paymentRequestFingerprint,
      MeterRegistry meterRegistry
  ) {
    this.paymentsRepository = paymentsRepository;
    this.idempotencyRepository = idempotencyRepository;
    this.paymentValidator = paymentValidator;
    this.bankPaymentMapper = bankPaymentMapper;
    this.bankSimulatorClient = bankSimulatorClient;
    this.paymentRequestFingerprint = paymentRequestFingerprint;
    this.meterRegistry = meterRegistry;

    this.paymentProcessingTimer =
        Timer.builder("payments.processing.time")
            .description("Time taken to process payment attempts")
            .register(meterRegistry);
  }

  public PostPaymentResponse getPaymentById(UUID id) {
    LOG.debug("Requesting payment with ID {}", id);

    return paymentsRepository.get(id)
        .orElseThrow(() -> {
          LOG.warn("Payment not found: id={}", id);
          return new EventProcessingException("Payment not found");
        });
  }

  public PostPaymentResponse processPayment(String idempotencyKey, PostPaymentRequest paymentRequest) {
    validateIdempotencyKey(idempotencyKey);

    try {
      paymentValidator.validate(paymentRequest);
    } catch (PaymentValidationException exception) {
      recordFailure(PaymentFailureReason.VALIDATION_ERROR);
      throw exception;
    }

    String fingerprint = paymentRequestFingerprint.create(paymentRequest);
    IdempotencyRecord newRecord = new IdempotencyRecord(fingerprint);

    // Atomically reserve the idempotency key. Only the thread that successfully
    // inserts a new record is responsible for calling the bank.
    IdempotencyRecord existingRecord = idempotencyRepository.putIfAbsent(idempotencyKey, newRecord);

    if (existingRecord != null) {
      return processDuplicateRequest(fingerprint, existingRecord);
    }

    try {
      PostPaymentResponse response = processNewPayment(paymentRequest);
      newRecord.getResponseFuture().complete(response);
      return response;
    } catch (RuntimeException exception) {
      newRecord.getResponseFuture().completeExceptionally(exception);
      throw exception;
    }
  }

  private PostPaymentResponse processNewPayment(PostPaymentRequest paymentRequest) {
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      LOG.info("Processing payment: currency={}, amount={}", paymentRequest.getCurrency(), paymentRequest.getAmount());

      BankPaymentRequest bankRequest = bankPaymentMapper.toBankRequest(paymentRequest);

      BankPaymentResponse bankResponse = bankSimulatorClient.authorize(bankRequest);

      PostPaymentResponse response = createPaymentResponse(paymentRequest, bankResponse);

      paymentsRepository.add(response);
      recordProcessed(response.getStatus());

      LOG.info("Payment processed: id={}, status={}", response.getId(), response.getStatus());

      return response;

    } catch (BankClientException exception) {
      recordFailure(PaymentFailureReason.BANK_REJECTED_REQUEST);
      throw exception;

    } catch (BankUnavailableException exception) {
      recordFailure(PaymentFailureReason.BANK_UNAVAILABLE);
      throw exception;

    } catch (RuntimeException exception) {
      recordFailure(PaymentFailureReason.UNEXPECTED_ERROR);
      throw exception;

    } finally {
      sample.stop(paymentProcessingTimer);
    }
  }

  private PostPaymentResponse processDuplicateRequest(String requestFingerprint, IdempotencyRecord existingRecord) {
    if (!existingRecord.getRequestFingerprint().equals(requestFingerprint)) {
      recordIdempotencyResult("CONFLICT");
      throw new IdempotencyConflictException("Idempotency key has already been used for a different request");
    }

    try {
      PostPaymentResponse response = existingRecord.getResponseFuture().get(IDEMPOTENCY_WAIT_SECONDS, TimeUnit.SECONDS);

      recordIdempotencyResult("REPLAYED");
      LOG.info("Returning response for repeated idempotent payment request");

      return response;
    } catch (TimeoutException exception) {
      recordIdempotencyResult("IN_PROGRESS");
      throw new IdempotencyRequestInProgressException("A request with this idempotency key is still being processed");
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Idempotent payment processing failed", cause);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for idempotent payment processing", exception);
    }
  }

  private void validateIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      recordFailure(PaymentFailureReason.VALIDATION_ERROR);
      throw new PaymentValidationException(List.of("Idempotency-Key header must not be blank"));
    }

    if (idempotencyKey.length() > 255) {
      recordFailure(PaymentFailureReason.VALIDATION_ERROR);
      throw new PaymentValidationException(List.of("Idempotency-Key header must not exceed 255 characters"));
    }
  }

  private void recordProcessed(PaymentStatus status) {
    meterRegistry.counter(
        "payments.processed",
        "status",
        status.name()
    ).increment();
  }

  private void recordFailure(PaymentFailureReason reason) {
    meterRegistry.counter(
        "payments.failed",
        "reason",
        reason.name()
    ).increment();
  }

  private void recordIdempotencyResult(String result) {
    meterRegistry.counter(
        "payments.idempotency",
        "result",
        result
    ).increment();
  }

  private PostPaymentResponse createPaymentResponse(PostPaymentRequest request, BankPaymentResponse bankResponse) {
    PostPaymentResponse response = new PostPaymentResponse();

    response.setId(UUID.randomUUID());
    response.setStatus(
        bankResponse.authorized()
            ? PaymentStatus.AUTHORIZED
            : PaymentStatus.DECLINED
    );
    response.setCardNumberLastFour(lastFourDigits(request.getCardNumber()));
    response.setExpiryMonth(request.getExpiryMonth());
    response.setExpiryYear(request.getExpiryYear());
    response.setCurrency(request.getCurrency().toUpperCase(Locale.ROOT));
    response.setAmount(request.getAmount());

    return response;
  }

  private String lastFourDigits(String cardNumber) {
    return cardNumber.substring(cardNumber.length() - 4);
  }
}
