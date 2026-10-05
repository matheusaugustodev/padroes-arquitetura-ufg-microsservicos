package br.lab.pagamento.messaging;

import br.lab.pagamento.config.RabbitConfig;
import br.lab.pagamento.evento.PedidoEvento;
import br.lab.pagamento.service.PagamentoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PedidoCriadoListener {

    private static final Logger log = LoggerFactory.getLogger(PedidoCriadoListener.class);

    private final PagamentoService service;

    public PedidoCriadoListener(PagamentoService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitConfig.PEDIDO_CRIADO_QUEUE)
    public void consumir(PedidoEvento evento) throws InterruptedException {
        log.info("correlationId={} Evento pedido.criado recebido: pedido {} produto {} quantidade {}",
                evento.correlationId(), evento.pedidoId(), evento.produtoId(), evento.quantidade());
        service.processar(evento);
    }
}
