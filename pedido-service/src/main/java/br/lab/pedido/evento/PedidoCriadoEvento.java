package br.lab.pedido.evento;

/** Evento publicado em pedidos.exchange com a routing key pedido.criado. */
public record PedidoCriadoEvento(Long pedidoId, Long produtoId, Integer quantidade, String correlationId) {
}
