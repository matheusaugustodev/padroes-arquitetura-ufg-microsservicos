package br.lab.estoque.controller;

import br.lab.estoque.dto.MensagemResponse;
import br.lab.estoque.dto.QuantidadeRequest;
import br.lab.estoque.model.Produto;
import br.lab.estoque.model.Reserva;
import br.lab.estoque.repository.ProdutoRepository;
import br.lab.estoque.repository.ReservaRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/produtos")
public class ProdutoController {

    private static final Logger log = LoggerFactory.getLogger(ProdutoController.class);
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ProdutoRepository repository;
    private final ReservaRepository reservaRepository;

    public ProdutoController(ProdutoRepository repository, ReservaRepository reservaRepository) {
        this.repository = repository;
        this.reservaRepository = reservaRepository;
    }

    // Endpoint 1 - consultar todos os produtos
    @GetMapping
    public List<Produto> listar() {
        return repository.findAll();
    }

    // Endpoint 2 - consultar produto
    @GetMapping("/{id}")
    public ResponseEntity<?> buscar(@PathVariable Long id) {
        return repository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MensagemResponse("Produto inexistente")));
    }

    // Endpoint 3 - reservar estoque
    @PutMapping("/{id}/reservar")
    @Transactional
    public ResponseEntity<MensagemResponse> reservar(
            @PathVariable Long id,
            @Valid @RequestBody QuantidadeRequest request,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {

        // Idempotencia: a mesma requisicao (mesmo correlationId) so baixa o estoque uma vez
        if (correlationId != null && reservaRepository.findByCorrelationId(correlationId).isPresent()) {
            log.info("correlationId={} Reserva ja processada - chamada repetida ignorada", correlationId);
            return ResponseEntity.ok(new MensagemResponse("Estoque ja reservado"));
        }

        if (!repository.existsById(id)) {
            log.warn("correlationId={} Produto {} inexistente", correlationId, id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MensagemResponse("Produto inexistente"));
        }

        int linhasAfetadas = repository.reservar(id, request.quantidade());
        if (linhasAfetadas == 0) {
            log.warn("correlationId={} Estoque insuficiente para produto {} (solicitado={})",
                    correlationId, id, request.quantidade());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MensagemResponse("Estoque insuficiente"));
        }

        if (correlationId != null) {
            reservaRepository.save(new Reserva(correlationId, id, request.quantidade(), false));
        }
        log.info("correlationId={} Produto {} reservado (quantidade={})",
                correlationId, id, request.quantidade());
        return ResponseEntity.ok(new MensagemResponse("Estoque reservado"));
    }

    // Endpoint extra - compensacao: devolve uma reserva ao estoque
    @PutMapping("/{id}/liberar")
    @Transactional
    public ResponseEntity<MensagemResponse> liberar(
            @PathVariable Long id,
            @Valid @RequestBody QuantidadeRequest request,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {

        if (correlationId != null) {
            return liberarReserva(id, request.quantidade(), correlationId);
        }
        if (repository.liberar(id, request.quantidade()) == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MensagemResponse("Produto inexistente"));
        }
        log.info("correlationId={} Reserva do produto {} liberada (quantidade={})",
                correlationId, id, request.quantidade());
        return ResponseEntity.ok(new MensagemResponse("Reserva liberada"));
    }

    /**
     * Libera pela reserva registrada: so devolve o que foi de fato reservado, e uma vez so.
     * Se a reserva nao existe (ex.: o Pedido desistiu por timeout antes de ela ser gravada),
     * grava uma reserva ja liberada; assim uma reserva atrasada com o mesmo correlationId e ignorada.
     */
    private ResponseEntity<MensagemResponse> liberarReserva(Long id, int quantidade, String correlationId) {
        Reserva reserva = reservaRepository.findByCorrelationId(correlationId).orElse(null);
        if (reserva == null) {
            reservaRepository.save(new Reserva(correlationId, id, quantidade, true));
            log.info("correlationId={} Nenhuma reserva do produto {} para liberar", correlationId, id);
            return ResponseEntity.ok(new MensagemResponse("Nenhuma reserva para liberar"));
        }
        if (reserva.isLiberada()) {
            log.info("correlationId={} Reserva ja liberada - chamada repetida ignorada", correlationId);
            return ResponseEntity.ok(new MensagemResponse("Reserva ja liberada"));
        }
        repository.liberar(reserva.getProdutoId(), reserva.getQuantidade());
        reserva.liberar();
        log.info("correlationId={} Reserva do produto {} liberada (quantidade={})",
                correlationId, reserva.getProdutoId(), reserva.getQuantidade());
        return ResponseEntity.ok(new MensagemResponse("Reserva liberada"));
    }
}
