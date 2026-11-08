package com.rodrigo.pedidos.pedido.repository;

import com.rodrigo.pedidos.pedido.domain.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {
}
