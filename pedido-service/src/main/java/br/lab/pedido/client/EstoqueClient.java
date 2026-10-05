package br.lab.pedido.client;

import br.lab.pedido.exception.NegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Comunicacao SINCRONA (REST) com o Estoque Service.
 * O Pedido Service nunca acessa o banco do Estoque: so conhece a API HTTP dele.
 */
@Component
public class EstoqueClient {

    private static final Logger log = LoggerFactory.getLogger(EstoqueClient.class);
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final RestTemplate restTemplate;
    private final String estoqueUrl;

    public EstoqueClient(RestTemplate restTemplate, @Value("${estoque.service.url}") String estoqueUrl) {
        this.restTemplate = restTemplate;
        this.estoqueUrl = estoqueUrl;
    }

    public void reservar(Long produtoId, int quantidade, String correlationId) {
        String url = estoqueUrl + "/produtos/" + produtoId + "/reservar";
        try {
            restTemplate.exchange(url, HttpMethod.PUT, corpo(quantidade, correlationId), Void.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NegocioException(HttpStatus.NOT_FOUND, "Produto inexistente");
        } catch (HttpClientErrorException.Conflict e) {
            throw new NegocioException(HttpStatus.CONFLICT, "Estoque insuficiente");
        } catch (ResourceAccessException e) {
            log.error("correlationId={} Estoque Service indisponivel: {}", correlationId, e.getMessage());
            throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE, "Estoque Service indisponivel");
        } catch (RestClientException e) {
            log.error("correlationId={} Erro ao chamar Estoque Service: {}", correlationId, e.getMessage());
            throw new NegocioException(HttpStatus.BAD_GATEWAY, "Erro ao reservar estoque");
        }
    }

    /** Compensacao: desfaz uma reserva ja realizada. */
    public void liberar(Long produtoId, int quantidade, String correlationId) {
        String url = estoqueUrl + "/produtos/" + produtoId + "/liberar";
        restTemplate.exchange(url, HttpMethod.PUT, corpo(quantidade, correlationId), Void.class);
    }

    private HttpEntity<Map<String, Integer>> corpo(int quantidade, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CORRELATION_HEADER, correlationId); // propaga o correlationId
        return new HttpEntity<>(Map.of("quantidade", quantidade), headers);
    }
}
