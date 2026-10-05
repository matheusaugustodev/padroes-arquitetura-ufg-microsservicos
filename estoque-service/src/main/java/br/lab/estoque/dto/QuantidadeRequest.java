package br.lab.estoque.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record QuantidadeRequest(@NotNull @Positive Integer quantidade) {
}
