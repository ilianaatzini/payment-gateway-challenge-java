package com.checkout.payment.gateway.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.model.GetPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "bank-simulator.base-url=http://localhost:8080"
    }
)
class PaymentGatewayEndToEndTest {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayEndToEndTest.class);

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Test
  void shouldCreateAndRetrievePaymentThroughRealBankSimulator() {
    String idempotencyKey = "e2e-" + UUID.randomUUID();
    PostPaymentRequest request = validPaymentRequest();

    LOG.info("Starting E2E test: create and retrieve payment");
    LOG.info(
        "Sending POST /payments with idempotencyKey={}, amount={}, currency={}",
        idempotencyKey,
        request.getAmount(),
        request.getCurrency()
    );

    ResponseEntity<PostPaymentResponse> createResponse =
        restTemplate.exchange(
            gatewayUrl("/payments"),
            HttpMethod.POST,
            new HttpEntity<>(request, headersWithIdempotencyKey(idempotencyKey)),
            PostPaymentResponse.class
        );

    LOG.info("POST /payments returned status={}", createResponse.getStatusCode());

    assertThat(createResponse.getStatusCode())
        .as("POST /payments should return 201 Created")
        .isEqualTo(HttpStatus.CREATED);

    PostPaymentResponse createdPayment = createResponse.getBody();

    assertThat(createdPayment)
        .as("Create-payment response body should not be null")
        .isNotNull();

    assertThat(createdPayment.getId())
        .as("Created payment should have an ID")
        .isNotNull();

    LOG.info(
        "Created payment with id={}, status={}", createdPayment.getId(), createdPayment.getStatus());

    LOG.info("Sending GET /payments/{}", createdPayment.getId());

    ResponseEntity<GetPaymentResponse> getResponse =
        restTemplate.getForEntity(
            gatewayUrl("/payments/" + createdPayment.getId()),
            GetPaymentResponse.class
        );

    LOG.info("GET /payments/{} returned status={}", createdPayment.getId(), getResponse.getStatusCode());

    assertThat(getResponse.getStatusCode())
        .as("GET /payments/{id} should return 200 OK")
        .isEqualTo(HttpStatus.OK);

    GetPaymentResponse retrievedPayment = getResponse.getBody();

    assertThat(retrievedPayment).as("Get-payment response body should not be null").isNotNull();

    assertThat(retrievedPayment.getId())
        .as("Retrieved payment ID should match the created payment ID")
        .isEqualTo(createdPayment.getId());

    LOG.info("Retrieved payment with id={}, status={}",
        retrievedPayment.getId(),
        retrievedPayment.getStatus()
    );

    LOG.info("Completed E2E test: create and retrieve payment");
  }

  @Test
  void shouldReplaySamePaymentForSameIdempotencyKey() {
    String idempotencyKey = "e2e-replay-" + UUID.randomUUID();
    PostPaymentRequest request = validPaymentRequest();

    HttpEntity<PostPaymentRequest> entity = new HttpEntity<>(request, headersWithIdempotencyKey(idempotencyKey));

    LOG.info("Starting E2E test: idempotent replay");
    LOG.info("Sending first POST /payments with idempotencyKey={}", idempotencyKey);

    ResponseEntity<PostPaymentResponse> firstResponse =
        restTemplate.exchange(
            gatewayUrl("/payments"),
            HttpMethod.POST,
            entity,
            PostPaymentResponse.class
        );

    LOG.info("First POST /payments returned status={}", firstResponse.getStatusCode());

    assertThat(firstResponse.getStatusCode())
        .as("First POST /payments should return 201 Created")
        .isEqualTo(HttpStatus.CREATED);

    assertThat(firstResponse.getBody())
        .as("First payment response body should not be null")
        .isNotNull();

    LOG.info(
        "First request created payment id={}, status={}",
        firstResponse.getBody().getId(),
        firstResponse.getBody().getStatus()
    );

    LOG.info("Replaying POST /payments with the same idempotencyKey={}", idempotencyKey);

    ResponseEntity<PostPaymentResponse> replayResponse =
        restTemplate.exchange(
            gatewayUrl("/payments"),
            HttpMethod.POST,
            entity,
            PostPaymentResponse.class
        );

    LOG.info("Replay POST /payments returned status={}", replayResponse.getStatusCode());

    assertThat(replayResponse.getStatusCode())
        .as("Replayed POST /payments should return 201 Created")
        .isEqualTo(HttpStatus.CREATED);

    assertThat(replayResponse.getBody())
        .as("Replay response body should not be null")
        .isNotNull();

    LOG.info(
        "Replay returned payment id={}, status={}",
        replayResponse.getBody().getId(),
        replayResponse.getBody().getStatus()
    );

    assertThat(replayResponse.getBody().getId())
        .as("Replay should return the original payment ID")
        .isEqualTo(firstResponse.getBody().getId());

    LOG.info(
        "Idempotent replay verified. Original id={}, replay id={}",
        firstResponse.getBody().getId(),
        replayResponse.getBody().getId()
    );

    LOG.info("Completed E2E test: idempotent replay");
  }

  @Test
  void shouldRejectDifferentRequestForSameIdempotencyKey() {
    String idempotencyKey = "e2e-conflict-" + UUID.randomUUID();

    PostPaymentRequest firstRequest = validPaymentRequest();
    PostPaymentRequest differentRequest = validPaymentRequest();

    differentRequest.setAmount(firstRequest.getAmount() + 100);

    LOG.info("Starting E2E test: idempotency conflict");
    LOG.info(
        "Sending first POST /payments with idempotencyKey={}, amount={}",
        idempotencyKey,
        firstRequest.getAmount()
    );

    ResponseEntity<PostPaymentResponse> firstResponse =
        restTemplate.exchange(
            gatewayUrl("/payments"),
            HttpMethod.POST,
            new HttpEntity<>(firstRequest, headersWithIdempotencyKey(idempotencyKey)),
            PostPaymentResponse.class
        );

    LOG.info("First POST /payments returned status={}", firstResponse.getStatusCode());

    assertThat(firstResponse.getStatusCode())
        .as("First POST /payments should return 201 Created")
        .isEqualTo(HttpStatus.CREATED);

    assertThat(firstResponse.getBody())
        .as("First payment response body should not be null")
        .isNotNull();

    LOG.info(
        "First request created payment id={}, amount={}",
        firstResponse.getBody().getId(),
        firstRequest.getAmount()
    );

    LOG.info(
        "Sending second POST /payments with same idempotencyKey={} and different amount={}",
        idempotencyKey,
        differentRequest.getAmount()
    );

    ResponseEntity<String> conflictResponse =
        restTemplate.exchange(
            gatewayUrl("/payments"),
            HttpMethod.POST,
            new HttpEntity<>(differentRequest, headersWithIdempotencyKey(idempotencyKey)),
            String.class
        );

    LOG.info(
        "Conflicting POST /payments returned status={}, body={}",
        conflictResponse.getStatusCode(),
        conflictResponse.getBody()
    );

    assertThat(conflictResponse.getStatusCode())
        .as("Reusing an idempotency key with a different request should return 409")
        .isEqualTo(HttpStatus.CONFLICT);

    LOG.info("Completed E2E test: idempotency conflict");
  }

  private HttpHeaders headersWithIdempotencyKey(String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  private String gatewayUrl(String path) {
    return "http://localhost:" + port + path;
  }

  private PostPaymentRequest validPaymentRequest() {
    PostPaymentRequest request = new PostPaymentRequest();

    request.setCardNumber("2222405343248877");
    request.setExpiryMonth(12);
    request.setExpiryYear(2027);
    request.setCurrency("GBP");
    request.setAmount(1050L);
    request.setCvv("123");

    return request;
  }
}
