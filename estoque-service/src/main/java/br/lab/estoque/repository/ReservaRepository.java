package br.lab.estoque.repository;

import br.lab.estoque.model.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservaRepository extends JpaRepository<Reserva, Long> {

    Optional<Reserva> findByCorrelationId(String correlationId);
}
