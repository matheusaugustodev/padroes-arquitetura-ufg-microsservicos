package br.lab.pagamento.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia do RabbitMQ.
 *
 * As declaracoes sao idempotentes: Pedido e Pagamento declaram a fila pedido.criado com os
 * MESMOS argumentos. Assim a fila existe (e guarda mensagens) mesmo que o Pagamento Service
 * nunca tenha sido iniciado.
 */
@Configuration
public class RabbitConfig {

    // ---- Evento pedido.criado (Pedido -> Pagamento) ----
    public static final String PEDIDOS_EXCHANGE = "pedidos.exchange";
    public static final String PEDIDO_CRIADO_QUEUE = "pedido.criado";
    public static final String PEDIDO_CRIADO_ROUTING_KEY = "pedido.criado";
    public static final String PEDIDOS_DLX = "pedidos.dlx";
    public static final String PEDIDO_CRIADO_DLQ = "pedido.criado.dlq";

    // ---- Evento pagamento.processado (Pagamento -> Pedido) ----
    public static final String PAGAMENTOS_EXCHANGE = "pagamentos.exchange";
    public static final String PAGAMENTO_PROCESSADO_QUEUE = "pagamento.processado";
    public static final String PAGAMENTO_PROCESSADO_ROUTING_KEY = "pagamento.processado";
    public static final String PAGAMENTOS_DLX = "pagamentos.dlx";
    public static final String PAGAMENTO_PROCESSADO_DLQ = "pagamento.processado.dlq";

    @Bean
    TopicExchange pedidosExchange() {
        return new TopicExchange(PEDIDOS_EXCHANGE, true, false);
    }

    @Bean
    Queue pedidoCriadoQueue() {
        return QueueBuilder.durable(PEDIDO_CRIADO_QUEUE)
                .deadLetterExchange(PEDIDOS_DLX)
                .deadLetterRoutingKey(PEDIDO_CRIADO_DLQ)
                .build();
    }

    @Bean
    Binding pedidoCriadoBinding() {
        return BindingBuilder.bind(pedidoCriadoQueue()).to(pedidosExchange()).with(PEDIDO_CRIADO_ROUTING_KEY);
    }

    @Bean
    DirectExchange pedidosDlx() {
        return new DirectExchange(PEDIDOS_DLX, true, false);
    }

    @Bean
    Queue pedidoCriadoDlq() {
        return QueueBuilder.durable(PEDIDO_CRIADO_DLQ).build();
    }

    @Bean
    Binding pedidoCriadoDlqBinding() {
        return BindingBuilder.bind(pedidoCriadoDlq()).to(pedidosDlx()).with(PEDIDO_CRIADO_DLQ);
    }

    @Bean
    TopicExchange pagamentosExchange() {
        return new TopicExchange(PAGAMENTOS_EXCHANGE, true, false);
    }

    @Bean
    Queue pagamentoProcessadoQueue() {
        return QueueBuilder.durable(PAGAMENTO_PROCESSADO_QUEUE)
                .deadLetterExchange(PAGAMENTOS_DLX)
                .deadLetterRoutingKey(PAGAMENTO_PROCESSADO_DLQ)
                .build();
    }

    @Bean
    Binding pagamentoProcessadoBinding() {
        return BindingBuilder.bind(pagamentoProcessadoQueue()).to(pagamentosExchange())
                .with(PAGAMENTO_PROCESSADO_ROUTING_KEY);
    }

    @Bean
    DirectExchange pagamentosDlx() {
        return new DirectExchange(PAGAMENTOS_DLX, true, false);
    }

    @Bean
    Queue pagamentoProcessadoDlq() {
        return QueueBuilder.durable(PAGAMENTO_PROCESSADO_DLQ).build();
    }

    @Bean
    Binding pagamentoProcessadoDlqBinding() {
        return BindingBuilder.bind(pagamentoProcessadoDlq()).to(pagamentosDlx()).with(PAGAMENTO_PROCESSADO_DLQ);
    }

    /**
     * Mensagens trafegam como JSON. "alwaysConvertToInferredType" faz o consumidor usar o tipo
     * do parametro do metodo (e nao o nome da classe Java do produtor), pois cada servico tem
     * sua propria classe de evento.
     */
    @Bean
    MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }
}
