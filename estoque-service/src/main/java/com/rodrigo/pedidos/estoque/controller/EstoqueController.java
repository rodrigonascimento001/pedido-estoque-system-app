package com.rodrigo.pedidos.estoque.controller;

import com.rodrigo.pedidos.estoque.dto.EstoqueDto;
import com.rodrigo.pedidos.estoque.repository.EstoqueRepository;
import com.rodrigo.pedidos.estoque.service.EstoqueService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/estoque")
public class EstoqueController {

    private final EstoqueService estoqueService;
    private final EstoqueRepository estoqueRepository;

    public EstoqueController(EstoqueService estoqueService, EstoqueRepository estoqueRepository) {
        this.estoqueService = estoqueService;
        this.estoqueRepository = estoqueRepository;
    }

    @PostMapping
    public ResponseEntity<EstoqueDto.Response> cadastrarOuRepor(@Valid @RequestBody EstoqueDto.Request request) {
        var estoque = estoqueService.cadastrarOuAtualizar(request.produtoId(), request.quantidade());
        return ResponseEntity.ok(EstoqueDto.Response.de(estoque));
    }

    @GetMapping("/{produtoId}")
    public ResponseEntity<EstoqueDto.Response> consultar(@PathVariable("produtoId") UUID produtoId) {
        var estoque = estoqueRepository.findById(produtoId)
                .orElseThrow(() -> new EntityNotFoundException("Produto não encontrado: " + produtoId));
        return ResponseEntity.ok(EstoqueDto.Response.de(estoque));
    }
}
