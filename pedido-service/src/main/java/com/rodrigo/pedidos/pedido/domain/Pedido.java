package com.rodrigo.pedidos.pedido.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
public class Pedido {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPedido status;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PedidoItem> itens = new ArrayList<>();

    @Column(nullable = false)
    private Instant criadoEm;

    @Column
    private Instant atualizadoEm;

    @Column
    private String motivoCancelamento;

    protected Pedido() {
    }

    public UUID getId() {
        return id;
    }

    public StatusPedido getStatus() {
        return status;
    }

    public List<PedidoItem> getItens() {
        return itens;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public String getMotivoCancelamento() {
        return motivoCancelamento;
    }

    public static Pedido novo(List<PedidoItem> itens) {
        Pedido pedido = new Pedido();
        pedido.status = StatusPedido.PENDENTE;
        pedido.criadoEm = Instant.now();
        itens.forEach(pedido::adicionarItem);
        return pedido;
    }

    public void adicionarItem(PedidoItem item) {
        item.setPedido(this);
        this.itens.add(item);
    }

    public void confirmar() {
        this.status = StatusPedido.CONFIRMADO;
        this.atualizadoEm = Instant.now();
    }

    public void cancelar(String motivo) {
        this.status = StatusPedido.CANCELADO;
        this.motivoCancelamento = motivo;
        this.atualizadoEm = Instant.now();
    }

    public BigDecimal valorTotal() {
        return itens.stream()
                .map(i -> i.getPrecoUnitario().multiply(BigDecimal.valueOf(i.getQuantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
