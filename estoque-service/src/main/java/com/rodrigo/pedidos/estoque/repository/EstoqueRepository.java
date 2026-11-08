package com.rodrigo.pedidos.estoque.repository;

import com.rodrigo.pedidos.estoque.domain.Estoque;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface EstoqueRepository extends JpaRepository<Estoque, UUID> {

    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select e from Estoque e where e.produtoId = :produtoId")
    Optional<Estoque> buscarParaAtualizar(@Param("produtoId") UUID produtoId);
}
