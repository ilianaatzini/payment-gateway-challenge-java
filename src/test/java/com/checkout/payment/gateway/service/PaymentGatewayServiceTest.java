package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankSimulatorClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.mapper.BankPaymentMapper;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.IdempotencyRepository;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.validation.PaymentValidator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayServiceTest {

  @Mock
  private PaymentsRepository paymentsRepository;

  private IdempotencyRepository idempotencyRepository;

  @Mock
  private PaymentValidator paymentValidator;

  @Mock
  private BankPaymentMapper bankPaymentMapper;

  @Mock
  private BankSimulatorClient bankSimulatorClient;


  private PaymentGatewayService paymentGatewayService;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    idempotencyRepository = new IdempotencyRepository();
    paymentGatewayService = new PaymentGatewayService(
        paymentsRepository,
        idempotencyRepository,
        paymentValidator,
        bankPaymentMapper,
        bankSimulatorClient,
        new PaymentRequestFingerprint(),
        meterRegistry
    );
  }

  @Test
  void shouldValidateMapAuthorizeStoreAndReturnAuthorizedPayment() {
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();
    BankPaymentResponse bankResponse = new BankPaymentResponse(true, "AUTH123");

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);

    when(bankSimulatorClient.authorize(bankRequest)).thenReturn(bankResponse);

    stubRepositoryAdd();

    PostPaymentResponse result = paymentGatewayService.processPayment("test-key", request);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isNotNull();
    assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(result.getCardNumberLastFour()).isEqualTo("8877");
    assertThat(result.getExpiryMonth()).isEqualTo(12);
    assertThat(result.getExpiryYear()).isEqualTo(2027);
    assertThat(result.getCurrency()).isEqualTo("GBP");
    assertThat(result.getAmount()).isEqualTo(1050L);

    ArgumentCaptor<PostPaymentResponse> storedPaymentCaptor = ArgumentCaptor.forClass(PostPaymentResponse.class);

    InOrder inOrder = inOrder(
        paymentValidator,
        bankPaymentMapper,
        bankSimulatorClient,
        paymentsRepository
    );

    inOrder.verify(paymentValidator).validate(request);
    inOrder.verify(bankPaymentMapper).toBankRequest(request);
    inOrder.verify(bankSimulatorClient).authorize(bankRequest);
    inOrder.verify(paymentsRepository).add(storedPaymentCaptor.capture());

    assertThat(storedPaymentCaptor.getValue()).isSameAs(result);
    assertThat(counter("payments.processed", "status", "AUTHORIZED")).isEqualTo(1.0);
  }

  @Test
  void shouldStoreDeclinedPaymentWhenBankDoesNotAuthorize() {
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);

    when(bankSimulatorClient.authorize(bankRequest))
        .thenReturn(new BankPaymentResponse(false, null));

    stubRepositoryAdd();

    PostPaymentResponse result = paymentGatewayService.processPayment("test-key", request);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.DECLINED);

    verify(paymentValidator).validate(request);
    verify(bankPaymentMapper).toBankRequest(request);
    verify(bankSimulatorClient).authorize(bankRequest);
    verify(paymentsRepository).add(result);

    assertThat(counter("payments.processed", "status", "DECLINED")).isEqualTo(1.0);
  }

  @Test
  void shouldStoreOnlyLastFourCardDigits() {
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);

    when(bankSimulatorClient.authorize(bankRequest))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));

    stubRepositoryAdd();

    paymentGatewayService.processPayment("test-key", request);

    ArgumentCaptor<PostPaymentResponse> captor = ArgumentCaptor.forClass(PostPaymentResponse.class);

    verify(paymentsRepository).add(captor.capture());

    PostPaymentResponse storedPayment = captor.getValue();

    assertThat(storedPayment.getCardNumberLastFour()).isEqualTo("8877");
    assertThat(storedPayment.getCardNumberLastFour()).doesNotContain("2222405343248877");
  }

  @Test
  void shouldNormalizeCurrencyBeforeReturningAndStoringPayment() {
    PostPaymentRequest request = validRequest();
    request.setCurrency("gbp");

    BankPaymentRequest bankRequest = new BankPaymentRequest(
        "2222405343248877",
        "12/2027",
        "GBP",
        1050L,
        "123"
    );

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);

    when(bankSimulatorClient.authorize(bankRequest))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));

    stubRepositoryAdd();

    PostPaymentResponse result = paymentGatewayService.processPayment("test-key", request);

    assertThat(result.getCurrency()).isEqualTo("GBP");
    verify(paymentsRepository).add(result);
  }

  @Test
  void shouldNotMapCallBankOrStoreWhenValidationFails() {
    PostPaymentRequest request = validRequest();

    PaymentValidationException exception = new PaymentValidationException(
        List.of("amount must be greater than zero")
    );

    doThrow(exception).when(paymentValidator).validate(request);

    assertThatThrownBy(() -> paymentGatewayService.processPayment("test-key", request))
        .isSameAs(exception);

    verify(paymentValidator).validate(request);
    verify(bankPaymentMapper, never()).toBankRequest(any());
    verify(bankSimulatorClient, never()).authorize(any());
    verify(paymentsRepository, never()).add(any());
    assertThat(counter("payments.failed", "reason", "VALIDATION_ERROR")).isEqualTo(1.0);
  }

  @Test
  void shouldRejectBlankIdempotencyKeyBeforeValidatingPayment() {
    PostPaymentRequest request = validRequest();

    assertThatThrownBy(() -> paymentGatewayService.processPayment("   ", request))
        .isInstanceOf(PaymentValidationException.class)
        .hasMessage("Invalid payment request")
        .satisfies(exception -> assertThat(((PaymentValidationException) exception).getErrors())
            .containsExactly("Idempotency-Key header must not be blank"));

    verifyNoInteractions(paymentValidator, bankPaymentMapper, bankSimulatorClient, paymentsRepository);
  }

  @Test
  void shouldRejectIdempotencyKeyLongerThan255Characters() {
    PostPaymentRequest request = validRequest();

    assertThatThrownBy(() -> paymentGatewayService.processPayment("a".repeat(256), request))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(exception -> assertThat(((PaymentValidationException) exception).getErrors())
            .containsExactly("Idempotency-Key header must not exceed 255 characters"));

    verifyNoInteractions(paymentValidator, bankPaymentMapper, bankSimulatorClient, paymentsRepository);
  }

  @Test
  void shouldNotStorePaymentWhenBankCallFails() {
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();

    BankUnavailableException exception = new BankUnavailableException("Bank simulator unavailable");

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);
    when(bankSimulatorClient.authorize(bankRequest)).thenThrow(exception);

    assertThatThrownBy(() -> paymentGatewayService.processPayment("test-key", request))
        .isSameAs(exception);

    verify(paymentValidator).validate(request);
    verify(bankPaymentMapper).toBankRequest(request);
    verify(bankSimulatorClient).authorize(bankRequest);
    verify(paymentsRepository, never()).add(any());
    assertThat(counter("payments.failed", "reason", "BANK_UNAVAILABLE")).isEqualTo(1.0);
  }

  @Test
  void shouldReplayResponseForSameKeyAndSameRequest() {
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);
    when(bankSimulatorClient.authorize(bankRequest))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));
    stubRepositoryAdd();

    PostPaymentResponse first = paymentGatewayService.processPayment("same-key", request);
    PostPaymentResponse second = paymentGatewayService.processPayment("same-key", request);

    assertThat(second).isSameAs(first);
    verify(paymentValidator, times(2)).validate(request);
    verify(bankPaymentMapper, times(1)).toBankRequest(request);
    verify(bankSimulatorClient, times(1)).authorize(bankRequest);
    verify(paymentsRepository, times(1)).add(any(PostPaymentResponse.class));
    assertThat(counter("payments.idempotency", "result", "REPLAYED")).isEqualTo(1.0);
  }

  @Test
  void shouldRejectSameKeyUsedForDifferentRequest() {
    PostPaymentRequest firstRequest = validRequest();
    PostPaymentRequest differentRequest = validRequest();
    differentRequest.setAmount(2000L);

    BankPaymentRequest bankRequest = validBankRequest();
    when(bankPaymentMapper.toBankRequest(firstRequest)).thenReturn(bankRequest);
    when(bankSimulatorClient.authorize(bankRequest))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));
    stubRepositoryAdd();

    paymentGatewayService.processPayment("same-key", firstRequest);

    assertThatThrownBy(() -> paymentGatewayService.processPayment("same-key", differentRequest))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessage("Idempotency key has already been used for a different request");

    verify(bankSimulatorClient, times(1)).authorize(any());
    verify(paymentsRepository, times(1)).add(any());
    assertThat(counter("payments.idempotency", "result", "CONFLICT")).isEqualTo(1.0);
  }

  @Test
  void shouldReplayBankFailureForSameKeyWithoutCallingBankAgain() {
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();
    BankUnavailableException bankFailure =
        new BankUnavailableException("Bank simulator unavailable");

    when(bankPaymentMapper.toBankRequest(request)).thenReturn(bankRequest);
    when(bankSimulatorClient.authorize(bankRequest)).thenThrow(bankFailure);

    assertThatThrownBy(() -> paymentGatewayService.processPayment("failure-key", request))
        .isSameAs(bankFailure);

    assertThatThrownBy(() -> paymentGatewayService.processPayment("failure-key", request))
        .isSameAs(bankFailure);

    verify(bankSimulatorClient, times(1)).authorize(bankRequest);
    verify(paymentsRepository, never()).add(any());
  }

  @Test
  void shouldReturnStoredPaymentById() {
    UUID paymentId = UUID.randomUUID();
    PostPaymentResponse storedPayment = storedPayment(paymentId);

    when(paymentsRepository.get(paymentId)).thenReturn(Optional.of(storedPayment));

    PostPaymentResponse result = paymentGatewayService.getPaymentById(paymentId);

    assertThat(result).isSameAs(storedPayment);
    verify(paymentsRepository).get(paymentId);
    verifyNoMoreInteractions(paymentsRepository);
  }

  @Test
  void shouldThrowWhenPaymentDoesNotExist() {
    UUID paymentId = UUID.randomUUID();
    when(paymentsRepository.get(paymentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> paymentGatewayService.getPaymentById(paymentId))
        .isInstanceOf(EventProcessingException.class)
        .hasMessage("Payment not found");

    verify(paymentsRepository).get(paymentId);
    verifyNoMoreInteractions(paymentsRepository);
  }

  @Test
  void shouldCallBankOnlyOnceForConcurrentRequestsWithSameIdempotencyKey()
      throws Exception {

    String idempotencyKey = "concurrent-key";
    PostPaymentRequest request = validRequest();
    BankPaymentRequest bankRequest = validBankRequest();
    BankPaymentResponse bankResponse =
        new BankPaymentResponse(true, "AUTH123");

    CountDownLatch bankCallStarted = new CountDownLatch(1);
    CountDownLatch allowBankCallToComplete = new CountDownLatch(1);

    when(bankPaymentMapper.toBankRequest(request))
        .thenReturn(bankRequest);

    when(bankSimulatorClient.authorize(bankRequest))
        .thenAnswer(invocation -> {
          bankCallStarted.countDown();

          boolean released =
              allowBankCallToComplete.await(5, TimeUnit.SECONDS);

          if (!released) {
            throw new IllegalStateException(
                "Timed out waiting to release the simulated bank call"
            );
          }

          return bankResponse;
        });

    stubRepositoryAdd();

    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<PostPaymentResponse> firstFuture =
          executor.submit(
              () -> paymentGatewayService.processPayment(
                  idempotencyKey,
                  request
              )
          );

      assertThat(bankCallStarted.await(5, TimeUnit.SECONDS))
          .as("The first request should reach the bank client")
          .isTrue();

      Future<PostPaymentResponse> secondFuture =
          executor.submit(
              () -> paymentGatewayService.processPayment(
                  idempotencyKey,
                  request
              )
          );

      /*
       * Both requests must have passed validation while the first bank
       * call remains blocked. The second request should then find the
       * existing idempotency record rather than calling the bank.
       */
      verify(paymentValidator, timeout(2_000).times(2))
          .validate(request);

      allowBankCallToComplete.countDown();

      PostPaymentResponse firstResponse =
          firstFuture.get(5, TimeUnit.SECONDS);

      PostPaymentResponse secondResponse =
          secondFuture.get(5, TimeUnit.SECONDS);

      assertThat(firstResponse).isNotNull();
      assertThat(secondResponse).isNotNull();

      assertThat(secondResponse.getId())
          .as("Both requests should return the same payment ID")
          .isEqualTo(firstResponse.getId());

      assertThat(secondResponse.getStatus())
          .as("Both requests should return the same payment status")
          .isEqualTo(firstResponse.getStatus());

      assertThat(secondResponse.getAmount())
          .isEqualTo(firstResponse.getAmount());

      assertThat(secondResponse.getCurrency())
          .isEqualTo(firstResponse.getCurrency());

      verify(bankPaymentMapper, times(1))
          .toBankRequest(request);

      verify(bankSimulatorClient, times(1))
          .authorize(bankRequest);

      verify(paymentsRepository, times(1))
          .add(any(PostPaymentResponse.class));

      assertThat(
          counter(
              "payments.idempotency",
              "result",
              "REPLAYED"
          )
      ).isEqualTo(1.0);

    } finally {
      allowBankCallToComplete.countDown();
      executor.shutdownNow();

      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
          .as("The test executor should terminate")
          .isTrue();
    }
  }

  private double counter(String name, String tagName, String tagValue) {
    return meterRegistry.get(name).tag(tagName, tagValue).counter().count();
  }

  private void stubRepositoryAdd() {
    when(paymentsRepository.add(any(PostPaymentResponse.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private PostPaymentRequest validRequest() {
    PostPaymentRequest request = new PostPaymentRequest();

    request.setCardNumber("2222405343248877");
    request.setExpiryMonth(12);
    request.setExpiryYear(2027);
    request.setCurrency("GBP");
    request.setAmount(1050L);
    request.setCvv("123");

    return request;
  }

  private BankPaymentRequest validBankRequest() {
    return new BankPaymentRequest(
        "2222405343248877",
        "12/2027",
        "GBP",
        1050L,
        "123"
    );
  }

  private PostPaymentResponse storedPayment(UUID paymentId) {
    PostPaymentResponse response = new PostPaymentResponse();

    response.setId(paymentId);
    response.setStatus(PaymentStatus.AUTHORIZED);
    response.setCardNumberLastFour("8877");
    response.setExpiryMonth(12);
    response.setExpiryYear(2027);
    response.setCurrency("GBP");
    response.setAmount(1050L);

    return response;
  }
}