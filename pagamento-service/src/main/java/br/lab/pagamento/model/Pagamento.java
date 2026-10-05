package br.lab.pagamento.model;

import jakarta.persistence.*;

@Entity
@Table(name = "pagamento")
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** unique: garante que um mesmo pedido nunca seja cobrado duas vezes (idempotencia). */
    @Column(unique = true)
    private Long pedidoId;

    @Column(length = 50)
    private String status; // APROVADO ou REJEITADO

    /** Campo extra para rastreabilidade. */
    private String correlationId;

    protected Pagamento() {
    }

    public Pagamento(Long pedidoId, String status, String correlationId) {
        this.pedidoId = pedidoId;
        this.status = status;
        this.correlationId = correlationId;
    }

    public Long getId() { return id; }
    public Long getPedidoId() { return pedidoId; }
    public String getStatus() { return status; }
    public String getCorrelationId() { return correlationId; }
}
