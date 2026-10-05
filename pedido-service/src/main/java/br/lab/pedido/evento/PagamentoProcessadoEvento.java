package br.lab.pedido.evento;

/** Evento recebido do Pagamento Service. status = APROVADO ou REJEITADO. */
public record PagamentoProcessadoEvento(Long pedidoId, String status, String correlationId) {
}
