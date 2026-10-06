package br.lab.pedido.service;

import br.lab.pedido.client.EstoqueClient;
import br.lab.pedido.config.RabbitConfig;
import br.lab.pedido.dto.CriarPedidoRequest;
import br.lab.pedido.evento.PagamentoProcessadoEvento;
import br.lab.pedido.evento.PedidoCriadoEvento;
import br.lab.pedido.exception.NegocioException;
import br.lab.pedido.model.Pedido;
import br.lab.pedido.model.StatusPedido;
import br.lab.pedido.repository.PedidoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepository repository;
    private final EstoqueClient estoqueClient;
    private final RabbitTemplate rabbitTemplate;

    public PedidoService(PedidoRepository repository, EstoqueClient estoqueClient, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.estoqueClient = estoqueClient;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Regra de negocio (Etapa 3):
     *   1. reservar estoque (REST)   -> se falhar, NAO cria pedido e NAO publica evento
     *   2. criar pedido (banco do Pedido)
     *   3. publicar evento pedido.criado (RabbitMQ)
     *
     * Os passos 1 e 2 estao em servicos/bancos diferentes: NAO existe transacao unica.
     * Por isso, se o passo 2 falhar, executamos uma COMPENSACAO (liberar a reserva).
     *
     * @param simularFalha experimento de consistencia: lanca erro apos reservar e antes de criar o pedido
     * @param compensar    se false, nao desfaz a reserva (mostra o estoque "preso")
     */
    public Pedido criar(CriarPedidoRequest request, String correlationId, boolean simularFalha, boolean compensar) {
        // 1. Reservar estoque (lanca NegocioException 404/409/503 se nao for possivel)
        estoqueClient.reservar(request.produtoId(), request.quantidade(), correlationId);

        // 2. Criar pedido
        Pedido pedido;
        try {
            if (simularFalha) {
                log.error("correlationId={} FALHA SIMULADA apos reservar o produto {} e antes de criar o pedido",
                        correlationId, request.produtoId());
                throw new IllegalStateException("Falha simulada apos a reserva do estoque");
            }
            pedido = repository.save(new Pedido(request.produtoId(), request.quantidade(), correlationId));
        } catch (RuntimeException e) {
            if (compensar) {
                compensarReserva(request, correlationId);
            } else {
                log.warn("correlationId={} Compensacao DESATIVADA: {} unidade(s) do produto {} ficaram reservadas sem pedido",
                        correlationId, request.quantidade(), request.produtoId());
            }
            throw new NegocioException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Falha ao criar pedido apos reservar estoque" + (compensar ? " (reserva desfeita)" : " (reserva NAO desfeita)"));
        }
        log.info("correlationId={} Pedido {} criado", correlationId, pedido.getId());

        // 3. Publicar evento
        publicarPedidoCriado(pedido);
        return pedido;
    }

    private void compensarReserva(CriarPedidoRequest request, String correlationId) {
        try {
            estoqueClient.liberar(request.produtoId(), request.quantidade(), correlationId);
            log.warn("correlationId={} Compensacao executada: reserva do produto {} desfeita",
                    correlationId, request.produtoId());
        } catch (RuntimeException ex) {
            // Em producao: registrar para nova tentativa (ex.: fila de compensacao / outbox)
            log.error("correlationId={} Compensacao FALHOU para o produto {}: {}",
                    correlationId, request.produtoId(), ex.getMessage());
        }
    }

    private void publicarPedidoCriado(Pedido pedido) {
        PedidoCriadoEvento evento = new PedidoCriadoEvento(
                pedido.getId(), pedido.getProdutoId(), pedido.getQuantidade(), pedido.getCorrelationId());
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.PEDIDOS_EXCHANGE, RabbitConfig.PEDIDO_CRIADO_ROUTING_KEY, evento,
                    msg -> {
                        msg.getMessageProperties().setCorrelationId(pedido.getCorrelationId());
                        return msg;
                    });
            log.info("correlationId={} Evento publicado {}", pedido.getCorrelationId(), pedido.getId());
        } catch (AmqpException e) {
            // O pedido ja foi salvo. Solucao robusta: padrao Transactional Outbox.
            log.error("correlationId={} Falha ao publicar evento do pedido {}: {}",
                    pedido.getCorrelationId(), pedido.getId(), e.getMessage());
        }
    }

    /**
     * Etapa 12: atualiza o status a partir do evento pagamento.processado.
     * Idempotente: so altera pedidos que ainda estao AGUARDANDO_PAGAMENTO.
     */
    @Transactional
    public void atualizarStatus(PagamentoProcessadoEvento evento) {
        Pedido pedido = repository.findById(evento.pedidoId()).orElse(null);
        if (pedido == null) {
            log.error("correlationId={} Pedido {} nao encontrado ao processar pagamento",
                    evento.correlationId(), evento.pedidoId());
            return;
        }
        if (pedido.getStatus() != StatusPedido.AGUARDANDO_PAGAMENTO) {
            log.info("correlationId={} Pedido {} ja estava {} - evento duplicado ignorado",
                    evento.correlationId(), pedido.getId(), pedido.getStatus());
            return;
        }

        if ("APROVADO".equals(evento.status())) {
            pedido.setStatus(StatusPedido.PAGO);
            log.info("correlationId={} Pedido {} atualizado para PAGO", evento.correlationId(), pedido.getId());
        } else {
            pedido.setStatus(StatusPedido.REJEITADO);
            log.info("correlationId={} Pedido {} atualizado para REJEITADO", evento.correlationId(), pedido.getId());
            // Compensacao de negocio: pagamento recusado devolve o item ao estoque.
            // Se falhar, a excecao desfaz a transacao (pedido continua AGUARDANDO_PAGAMENTO) e o
            // RabbitMQ entrega o evento de novo (3 tentativas, depois DLQ). Repetir e seguro porque
            // o Estoque libera uma unica vez por correlationId.
            estoqueClient.liberar(pedido.getProdutoId(), pedido.getQuantidade(), evento.correlationId());
            log.info("correlationId={} Estoque do pedido {} devolvido (pagamento rejeitado)",
                    evento.correlationId(), pedido.getId());
        }
    }
}
