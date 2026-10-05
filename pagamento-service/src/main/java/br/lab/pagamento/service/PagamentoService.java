package br.lab.pagamento.service;

import br.lab.pagamento.config.RabbitConfig;
import br.lab.pagamento.evento.PagamentoProcessadoEvento;
import br.lab.pagamento.evento.PedidoEvento;
import br.lab.pagamento.model.Pagamento;
import br.lab.pagamento.repository.PagamentoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PagamentoService {

    private static final Logger log = LoggerFactory.getLogger(PagamentoService.class);

    private final PagamentoRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final long tempoProcessamentoMs;
    private final double taxaAprovacao;
    private final Long falharPedidoId;

    public PagamentoService(PagamentoRepository repository,
                            RabbitTemplate rabbitTemplate,
                            @Value("${pagamento.tempo-processamento-ms}") long tempoProcessamentoMs,
                            @Value("${pagamento.taxa-aprovacao}") double taxaAprovacao,
                            @Value("${simulacao.falhar-pedido-id:}") Long falharPedidoId) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.tempoProcessamentoMs = tempoProcessamentoMs;
        this.taxaAprovacao = taxaAprovacao;
        this.falharPedidoId = falharPedidoId;
    }

    public void processar(PedidoEvento evento) throws InterruptedException {
        String correlationId = evento.correlationId();

        // Simulacao para a Investigacao de Incidente: falha no processamento de um pedido especifico
        if (falharPedidoId != null && falharPedidoId.equals(evento.pedidoId())) {
            log.error("correlationId={} ERRO ao processar pagamento do pedido {}: gateway de pagamento indisponivel",
                    correlationId, evento.pedidoId());
            throw new IllegalStateException("Gateway de pagamento indisponivel (simulado)");
        }

        // Idempotencia: se a mensagem for reentregue, nao cobra duas vezes
        Optional<Pagamento> existente = repository.findByPedidoId(evento.pedidoId());
        if (existente.isPresent()) {
            log.warn("correlationId={} Pagamento do pedido {} ja processado ({}) - reenviando resultado",
                    correlationId, evento.pedidoId(), existente.get().getStatus());
            publicarResultado(existente.get());
            return;
        }

        Thread.sleep(tempoProcessamentoMs); // simula a chamada a um gateway de pagamento

        // 80% aprovado / 20% rejeitado
        String status = ThreadLocalRandom.current().nextDouble() < taxaAprovacao ? "APROVADO" : "REJEITADO";
        Pagamento pagamento = repository.save(new Pagamento(evento.pedidoId(), status, correlationId));

        if ("APROVADO".equals(status)) {
            log.info("correlationId={} Pagamento aprovado {}", correlationId, evento.pedidoId());
        } else {
            log.info("correlationId={} Pagamento rejeitado {}", correlationId, evento.pedidoId());
        }

        publicarResultado(pagamento);
    }

    /** Etapa 12: informa o resultado ao Pedido Service via RabbitMQ (nunca acessando o banco dele). */
    private void publicarResultado(Pagamento pagamento) {
        PagamentoProcessadoEvento resultado = new PagamentoProcessadoEvento(
                pagamento.getPedidoId(), pagamento.getStatus(), pagamento.getCorrelationId());
        rabbitTemplate.convertAndSend(RabbitConfig.PAGAMENTOS_EXCHANGE,
                RabbitConfig.PAGAMENTO_PROCESSADO_ROUTING_KEY, resultado, msg -> {
                    msg.getMessageProperties().setCorrelationId(pagamento.getCorrelationId());
                    return msg;
                });
        log.info("correlationId={} Evento pagamento.processado publicado {} ({})",
                pagamento.getCorrelationId(), pagamento.getPedidoId(), pagamento.getStatus());
    }
}
