package br.lab.pagamento.evento;

/** Evento publicado em pagamentos.exchange com a routing key pagamento.processado. */
public record PagamentoProcessadoEvento(Long pedidoId, String status, String correlationId) {
}
