package com.checkout.payment.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.exception.BankClientException;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BankSimulatorClientTest {

  private static final String BANK_BASE_URL = "http://bank-simulator";

  private static final String PAYMENTS_URL = BANK_BASE_URL + "/payments";

  private MockRestServiceServer server;
  private BankSimulatorClient client;

  @BeforeEach
  void setUp() {
    RestTemplate restTemplate = new RestTemplate();

    server = MockRestServiceServer
        .bindTo(restTemplate)
        .build();

    client = new BankSimulatorClient(
        restTemplate,
        BANK_BASE_URL
    );
  }

  @Test
  void shouldReturnAuthorizedBankResponse() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            )
        )
        .andExpect(content().json("""
            {
              "card_number": "2222405343248877",
              "expiry_date": "12/2027",
              "currency": "GBP",
              "amount": 1050,
              "cvv": "123"
            }
            """))
        .andRespond(
            withSuccess(
                """
                {
                  "authorized": true,
                  "authorization_code": "AUTH123"
                }
                """,
                MediaType.APPLICATION_JSON
            )
        );

    BankPaymentResponse response = client.authorize(validRequest());

    assertThat(response).isNotNull();
    assertThat(response.authorized()).isTrue();
    assertThat(response.authorizationCode()).isEqualTo("AUTH123");

    server.verify();
  }

  @Test
  void shouldReturnDeclinedBankResponse() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {
                  "authorized": false,
                  "authorization_code": null
                }
                """,
                MediaType.APPLICATION_JSON
            )
        );

    BankPaymentResponse response = client.authorize(validRequest());

    assertThat(response).isNotNull();
    assertThat(response.authorized()).isFalse();
    assertThat(response.authorizationCode()).isNull();

    server.verify();
  }

  @Test
  void shouldThrowBankClientExceptionForBadRequest() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "message": "Invalid payment request"
                    }
                    """)
        );

    assertThatThrownBy(
        () -> client.authorize(validRequest())
    )
        .isInstanceOf(BankClientException.class)
        .hasMessage("Bank rejected the gateway request")
        .hasCauseInstanceOf(
            org.springframework.web.client
                .HttpClientErrorException.class
        );

    server.verify();
  }

  @Test
  void shouldThrowBankClientExceptionForUnauthorizedResponse() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
        );

    assertThatThrownBy(
        () -> client.authorize(validRequest())
    )
        .isInstanceOf(BankClientException.class)
        .hasMessage("Bank rejected the gateway request");

    server.verify();
  }

  @Test
  void shouldThrowBankUnavailableExceptionForServerError() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        );

    assertThatThrownBy(
        () -> client.authorize(validRequest())
    )
        .isInstanceOf(BankUnavailableException.class)
        .hasMessage("Bank simulator is unavailable")
        .hasCauseInstanceOf(
            org.springframework.web.client
                .HttpServerErrorException.class
        );

    server.verify();
  }

  @Test
  void shouldThrowBankUnavailableExceptionForInternalServerError() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        );

    assertThatThrownBy(
        () -> client.authorize(validRequest())
    )
        .isInstanceOf(BankUnavailableException.class)
        .hasMessage("Bank simulator is unavailable");

    server.verify();
  }

  @Test
  void shouldThrowBankUnavailableExceptionForEmptyResponse() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "",
                MediaType.APPLICATION_JSON
            )
        );

    assertThatThrownBy(
        () -> client.authorize(validRequest())
    )
        .isInstanceOf(BankUnavailableException.class)
        .hasMessage(
            "Bank simulator returned an empty response"
        );

    server.verify();
  }

  @Test
  void shouldThrowBankUnavailableExceptionForInvalidResponseBody() {
    server.expect(requestTo(PAYMENTS_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {
                  "authorized":
                }
                """,
                MediaType.APPLICATION_JSON
            )
        );

    assertThatThrownBy(
        () -> client.authorize(validRequest())
    )
        .isInstanceOf(BankUnavailableException.class)
        .hasMessage(
            "Unexpected error communicating with bank simulator"
        );

    server.verify();
  }

  private BankPaymentRequest validRequest() {
    return new BankPaymentRequest(
        "2222405343248877",
        "12/2027",
        "GBP",
        1050L,
        "123"
    );
  }
}