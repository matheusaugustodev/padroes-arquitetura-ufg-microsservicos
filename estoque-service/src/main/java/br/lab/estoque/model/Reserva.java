package br.lab.estoque.model;

import jakarta.persistence.*;

/**
 * Registro de cada reserva, identificada pelo correlationId do pedido.
 * Torna reservar/liberar idempotentes: repetir a chamada (retry, timeout) nao baixa nem devolve
 * o estoque duas vezes.
 */
@Entity
@Table(name = "reserva")
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String correlationId;

    private Long produtoId;

    private Integer quantidade;

    private boolean liberada;

    protected Reserva() {
    }

    public Reserva(String correlationId, Long produtoId, Integer quantidade, boolean liberada) {
        this.correlationId = correlationId;
        this.produtoId = produtoId;
        this.quantidade = quantidade;
        this.liberada = liberada;
    }

    public Long getProdutoId() {
        return produtoId;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public boolean isLiberada() {
        return liberada;
    }

    public void liberar() {
        this.liberada = true;
    }
}
