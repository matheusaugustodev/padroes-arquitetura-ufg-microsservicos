package br.lab.pedido.controller;

import br.lab.pedido.dto.CriarPedidoRequest;
import br.lab.pedido.dto.MensagemResponse;
import br.lab.pedido.exception.NegocioException;
import br.lab.pedido.model.Pedido;
import br.lab.pedido.repository.PedidoRepository;
import br.lab.pedido.service.PedidoService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private static final Logger log = LoggerFactory.getLogger(PedidoController.class);
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final PedidoService service;
    private final PedidoRepository repository;

    public PedidoController(PedidoService service, PedidoRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<?> criar(@Valid @RequestBody CriarPedidoRequest request,
                                   @RequestParam(defaultValue = "false") boolean simularFalha,
                                   @RequestParam(defaultValue = "true") boolean compensar) {
        // Etapa 11: um correlationId unico por requisicao, propagado para Estoque e RabbitMQ
        String correlationId = UUID.randomUUID().toString();
        log.info("correlationId={} Requisicao de pedido recebida: produto {} quantidade {}",
                correlationId, request.produtoId(), request.quantidade());
        try {
            Pedido pedido = service.criar(request, correlationId, simularFalha, compensar);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header(CORRELATION_HEADER, correlationId)
                    .body(pedido);
        } catch (NegocioException e) {
            log.warn("correlationId={} Pedido NAO criado: {}", correlationId, e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .header(CORRELATION_HEADER, correlationId)
                    .body(new MensagemResponse(e.getMessage(), correlationId));
        }
    }

    @GetMapping
    public List<Pedido> listar() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> buscar(@PathVariable Long id) {
        return repository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MensagemResponse("Pedido inexistente", null)));
    }
}
