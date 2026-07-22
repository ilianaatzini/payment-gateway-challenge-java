package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankClientException;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class BankSimulatorClient {

  private static final Logger LOG = LoggerFactory.getLogger(BankSimulatorClient.class);

  private static final String PAYMENTS_PATH = "/payments";

  private final RestTemplate restTemplate;
  private final String bankSimulatorBaseUrl;

  public BankSimulatorClient(RestTemplate restTemplate,
                              @Value("${bank-simulator.base-url}") String bankSimulatorBaseUrl) {
    this.restTemplate = restTemplate;
    this.bankSimulatorBaseUrl = bankSimulatorBaseUrl;
  }

  public BankPaymentResponse authorize(BankPaymentRequest request) {
    LOG.debug("Sending authorization request to bank simulator");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<BankPaymentRequest> entity = new HttpEntity<>(request, headers);

    try {
      ResponseEntity<BankPaymentResponse> response =
          restTemplate.postForEntity(
              paymentUri(),
              entity,
              BankPaymentResponse.class
          );

      BankPaymentResponse body = response.getBody();

      if (body == null) {
        throw new BankUnavailableException("Bank simulator returned an empty response");
      }

      LOG.debug("Received authorization response from bank simulator");

      return body;

    } catch (HttpClientErrorException exception) {
      LOG.warn("Bank simulator rejected request with status {}", exception.getStatusCode());
      throw new BankClientException("Bank rejected the gateway request", exception);

    } catch (HttpServerErrorException exception) {
      LOG.warn("Bank simulator returned server error with status {}", exception.getStatusCode());
      throw new BankUnavailableException("Bank simulator is unavailable", exception);

    } catch (ResourceAccessException exception) {
      LOG.warn("Failed to communicate with bank simulator", exception);
      throw new BankUnavailableException("Failed to communicate with bank simulator", exception);

    } catch (RestClientException exception) {
      LOG.warn("Unexpected error communicating with bank simulator", exception);
      throw new BankUnavailableException("Unexpected error communicating with bank simulator", exception);
    }
  }

  private URI paymentUri() {
    return URI.create(bankSimulatorBaseUrl + PAYMENTS_PATH);
  }
}