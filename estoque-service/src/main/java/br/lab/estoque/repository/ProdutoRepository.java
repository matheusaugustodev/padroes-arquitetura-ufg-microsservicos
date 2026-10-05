package br.lab.estoque.repository;

import br.lab.estoque.model.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    /**
     * Baixa o estoque de forma ATOMICA: a verificacao de saldo e a subtracao acontecem
     * no mesmo UPDATE. Assim, duas requisicoes simultaneas (ou duas instancias do servico)
     * nunca deixam a quantidade negativa. Retorna 1 se reservou, 0 se nao havia saldo.
     */
    @Modifying
    @Query("UPDATE Produto p SET p.quantidade = p.quantidade - :qtd " +
           "WHERE p.id = :id AND p.quantidade >= :qtd")
    int reservar(@Param("id") Long id, @Param("qtd") int quantidade);

    /** Devolve quantidade ao estoque (usado na compensacao). */
    @Modifying
    @Query("UPDATE Produto p SET p.quantidade = p.quantidade + :qtd WHERE p.id = :id")
    int liberar(@Param("id") Long id, @Param("qtd") int quantidade);
}
