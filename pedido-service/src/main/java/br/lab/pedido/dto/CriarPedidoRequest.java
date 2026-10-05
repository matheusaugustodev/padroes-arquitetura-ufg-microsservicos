package br.lab.pedido.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CriarPedidoRequest(@NotNull Long produtoId, @NotNull @Positive Integer quantidade) {
}
