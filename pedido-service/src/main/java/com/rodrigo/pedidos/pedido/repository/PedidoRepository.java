package com.rodrigo.pedidos.pedido.repository;

import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.domain.StatusPedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Pedido p
            set p.status = :destino, p.atualizadoEm = :agora, p.motivoCancelamento = :motivo
            where p.id = :pedidoId and p.status = :pendente
            """)
    int transicionarSePendente(@Param("pedidoId") UUID pedidoId,
                               @Param("pendente") StatusPedido pendente,
                               @Param("destino") StatusPedido destino,
                               @Param("motivo") String motivo,
                               @Param("agora") Instant agora);
}
