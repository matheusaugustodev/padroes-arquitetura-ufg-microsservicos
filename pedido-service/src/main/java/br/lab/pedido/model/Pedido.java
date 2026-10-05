package br.lab.pedido.model;

import jakarta.persistence.*;

@Entity
@Table(name = "pedido")
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long produtoId;

    private Integer quantidade;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private StatusPedido status;

    /** Campo extra (nao previsto no enunciado) para facilitar a investigacao de incidentes. */
    private String correlationId;

    protected Pedido() {
    }

    public Pedido(Long produtoId, Integer quantidade, String correlationId) {
        this.produtoId = produtoId;
        this.quantidade = quantidade;
        this.correlationId = correlationId;
        this.status = StatusPedido.AGUARDANDO_PAGAMENTO; // estado inicial obrigatorio
    }

    public Long getId() { return id; }
    public Long getProdutoId() { return produtoId; }
    public Integer getQuantidade() { return quantidade; }
    public StatusPedido getStatus() { return status; }
    public String getCorrelationId() { return correlationId; }

    public void setStatus(StatusPedido status) { this.status = status; }
}
