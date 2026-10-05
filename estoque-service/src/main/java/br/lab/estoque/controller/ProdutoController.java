package br.lab.estoque.controller;

import br.lab.estoque.dto.MensagemResponse;
import br.lab.estoque.dto.QuantidadeRequest;
import br.lab.estoque.model.Produto;
import br.lab.estoque.repository.ProdutoRepository;
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

    public ProdutoController(ProdutoRepository repository) {
        this.repository = repository;
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

        if (repository.liberar(id, request.quantidade()) == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MensagemResponse("Produto inexistente"));
        }
        log.info("correlationId={} Reserva do produto {} liberada (quantidade={})",
                correlationId, id, request.quantidade());
        return ResponseEntity.ok(new MensagemResponse("Reserva liberada"));
    }
}
