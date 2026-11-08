package com.rodrigo.pedidos.estoque.domain;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "estoque")
public class Estoque {

    @Id
    private UUID produtoId;

    @Column(nullable = false)
    private int quantidadeDisponivel;

    // Lock otimista: evita condição de corrida quando dois pedidos
    // tentam reservar o mesmo produto ao mesmo tempo.
    @Version
    private long versao;

    protected Estoque() {
    }

    public UUID getProdutoId() {
        return produtoId;
    }

    public int getQuantidadeDisponivel() {
        return quantidadeDisponivel;
    }

    public long getVersao() {
        return versao;
    }

    public Estoque(UUID produtoId, int quantidadeDisponivel) {
        this.produtoId = produtoId;
        this.quantidadeDisponivel = quantidadeDisponivel;
    }

    public boolean possuiSaldo(int quantidade) {
        return this.quantidadeDisponivel >= quantidade;
    }

    public void reservar(int quantidade) {
        if (!possuiSaldo(quantidade)) {
            throw new SaldoInsuficienteException(produtoId, quantidade, quantidadeDisponivel);
        }
        this.quantidadeDisponivel -= quantidade;
    }

    public void repor(int quantidade) {
        this.quantidadeDisponivel += quantidade;
    }
}
