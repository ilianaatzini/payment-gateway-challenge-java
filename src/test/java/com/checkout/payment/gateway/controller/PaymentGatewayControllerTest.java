package com.checkout.payment.gateway.controller;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankSimulatorClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankClientException;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.IdempotencyRepository;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentGatewayControllerTest {

  private static final String IDEMPOTENCY_KEY = "controller-test-key";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private PaymentsRepository paymentsRepository;

  @Autowired
  private IdempotencyRepository idempotencyRepository;

  @MockBean
  private BankSimulatorClient bankSimulatorClient;

  @BeforeEach
  void setUp() {
    paymentsRepository.clear();
    idempotencyRepository.clear();
  }

  @Test
  void shouldReturnExistingPayment() throws Exception {
    PostPaymentResponse payment = storedPayment();
    paymentsRepository.add(payment);

    mvc.perform(get("/payments/{id}", payment.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(payment.getId().toString()))
        .andExpect(jsonPath("$.status").value(PaymentStatus.AUTHORIZED.getName()))
        .andExpect(jsonPath("$.card_number_last_four").value("4321"))
        .andExpect(jsonPath("$.expiry_month").value(12))
        .andExpect(jsonPath("$.expiry_year").value(2027))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.amount").value(10));
  }

  @Test
  void shouldReturn404WhenPaymentDoesNotExist() throws Exception {
    mvc.perform(get("/payments/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Payment not found"));
  }

  @Test
  void shouldReturn400WhenPaymentIdIsMalformed() throws Exception {
    mvc.perform(get("/payments/{id}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid payment id"));
  }

  @Test
  void shouldCreateAuthorizedPayment() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));

    mvc.perform(postPayment(IDEMPOTENCY_KEY, validRequest()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.status").value(PaymentStatus.AUTHORIZED.getName()))
        .andExpect(jsonPath("$.card_number_last_four").value("8877"))
        .andExpect(jsonPath("$.expiry_month").value(12))
        .andExpect(jsonPath("$.expiry_year").value(2027))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(1050));
  }

  @Test
  void shouldCreateDeclinedPayment() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenReturn(new BankPaymentResponse(false, null));

    mvc.perform(postPayment(IDEMPOTENCY_KEY, validRequest()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.status").value(PaymentStatus.DECLINED.getName()))
        .andExpect(jsonPath("$.card_number_last_four").value("8877"))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(1050));
  }

  @Test
  void shouldPersistCreatedPayment() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));

    String responseBody = mvc.perform(postPayment(IDEMPOTENCY_KEY, validRequest()))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();

    PostPaymentResponse createdPayment =
        objectMapper.readValue(responseBody, PostPaymentResponse.class);

    mvc.perform(get("/payments/{id}", createdPayment.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(createdPayment.getId().toString()))
        .andExpect(jsonPath("$.status").value(PaymentStatus.AUTHORIZED.getName()))
        .andExpect(jsonPath("$.card_number_last_four").value("8877"))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(1050));
  }

  @Test
  void shouldReplayResponseForSameIdempotencyKeyAndSameRequest() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));

    String firstResponse = mvc.perform(postPayment("replay-key", validRequest()))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();

    String secondResponse = mvc.perform(postPayment("replay-key", validRequest()))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();

    PostPaymentResponse first = objectMapper.readValue(firstResponse, PostPaymentResponse.class);
    PostPaymentResponse second = objectMapper.readValue(secondResponse, PostPaymentResponse.class);

    org.assertj.core.api.Assertions.assertThat(second.getId()).isEqualTo(first.getId());
    verify(bankSimulatorClient, times(1)).authorize(any(BankPaymentRequest.class));
  }

  @Test
  void shouldReturn409WhenIdempotencyKeyIsReusedForDifferentRequest() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenReturn(new BankPaymentResponse(true, "AUTH123"));

    mvc.perform(postPayment("conflict-key", validRequest()))
        .andExpect(status().isCreated());

    PostPaymentRequest differentRequest = validRequest();
    differentRequest.setAmount(2000L);

    mvc.perform(postPayment("conflict-key", differentRequest))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message")
            .value("Idempotency key has already been used for a different request"));

    verify(bankSimulatorClient, times(1)).authorize(any(BankPaymentRequest.class));
  }

  @Test
  void shouldReturn400WhenIdempotencyKeyIsMissing() throws Exception {
    mvc.perform(
            post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest()))
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Required request header is missing: Idempotency-Key"));
  }

  @Test
  void shouldReturn400WhenIdempotencyKeyIsBlank() throws Exception {
    mvc.perform(postPayment("   ", validRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid payment request"))
        .andExpect(jsonPath("$.errors[0]")
            .value("Idempotency-Key header must not be blank"));
  }

  @Test
  void shouldReturn400WhenIdempotencyKeyIsTooLong() throws Exception {
    mvc.perform(postPayment("a".repeat(256), validRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid payment request"))
        .andExpect(jsonPath("$.errors[0]")
            .value("Idempotency-Key header must not exceed 255 characters"));
  }

  @Test
  void shouldReturn400WhenPaymentRequestIsInvalid() throws Exception {
    PostPaymentRequest request = validRequest();
    request.setCardNumber(null);
    request.setAmount(0L);

    mvc.perform(postPayment(IDEMPOTENCY_KEY, request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid payment request"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors", hasItems(
            "card_number is required",
            "amount must be greater than zero"
        )));
  }

  @Test
  void shouldReturn400WhenRequestBodyIsMalformed() throws Exception {
    mvc.perform(
            post("/payments")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "card_number": "2222405343248877",
                      "expiry_month":
                    }
                    """)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed request body"));
  }

  @Test
  void shouldReturn502WhenBankRejectsRequest() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenThrow(new BankClientException("Bank rejected the gateway request"));

    mvc.perform(postPayment(IDEMPOTENCY_KEY, validRequest()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.message").value("Bank rejected the gateway request"));
  }

  @Test
  void shouldReturn503WhenBankIsUnavailable() throws Exception {
    when(bankSimulatorClient.authorize(any(BankPaymentRequest.class)))
        .thenThrow(new BankUnavailableException("Bank simulator is unavailable"));

    mvc.perform(postPayment(IDEMPOTENCY_KEY, validRequest()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("Bank simulator is unavailable"));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postPayment(
      String idempotencyKey,
      PostPaymentRequest request
  ) throws Exception {
    return post("/payments")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request));
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

  private PostPaymentResponse storedPayment() {
    PostPaymentResponse payment = new PostPaymentResponse();
    payment.setId(UUID.randomUUID());
    payment.setAmount(10L);
    payment.setCurrency("USD");
    payment.setStatus(PaymentStatus.AUTHORIZED);
    payment.setExpiryMonth(12);
    payment.setExpiryYear(2027);
    payment.setCardNumberLastFour("4321");
    return payment;
  }
}
