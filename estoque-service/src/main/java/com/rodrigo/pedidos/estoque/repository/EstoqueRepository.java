package com.rodrigo.pedidos.estoque.repository;

import com.rodrigo.pedidos.estoque.domain.Estoque;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstoqueRepository extends JpaRepository<Estoque, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Estoque e where e.produtoId in :produtoIds order by e.produtoId")
    List<Estoque> bloquearProdutos(@Param("produtoIds") Collection<UUID> produtoIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE estoque
            SET quantidade_disponivel = quantidade_disponivel - :quantidade,
                versao = versao + 1
            WHERE produto_id = :produtoId
              AND quantidade_disponivel >= :quantidade
            """, nativeQuery = true)
    int decrementarSeDisponivel(@Param("produtoId") UUID produtoId,
                                @Param("quantidade") int quantidade);
}
