package br.lab.estoque.config;

import br.lab.estoque.model.Produto;
import br.lab.estoque.repository.ProdutoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** Carrega os dados iniciais exigidos pelo enunciado, apenas se a tabela estiver vazia. */
@Component
public class DadosIniciais implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DadosIniciais.class);

    private final ProdutoRepository repository;

    public DadosIniciais(ProdutoRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() == 0) {
            repository.saveAll(List.of(
                    new Produto(1L, "Notebook", 10),
                    new Produto(2L, "Mouse", 50),
                    new Produto(3L, "Teclado", 20)
            ));
            log.info("Dados iniciais de produtos carregados");
        }
    }
}
