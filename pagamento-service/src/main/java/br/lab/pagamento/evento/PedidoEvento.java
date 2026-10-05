package br.lab.pagamento.evento;

/** Evento recebido da fila pedido.criado (contrato definido pelo Pedido Service). */
public record PedidoEvento(Long pedidoId, Long produtoId, Integer quantidade, String correlationId) {
}
