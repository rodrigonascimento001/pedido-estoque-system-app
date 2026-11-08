package com.rodrigo.pedidos.pedido.controller;

import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.dto.PedidoResponse;
import com.rodrigo.pedidos.pedido.service.PedidoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final PedidoService pedidoService;

    public PedidoController(PedidoService pedidoService) {
        this.pedidoService = pedidoService;
    }

    @PostMapping
    public ResponseEntity<PedidoResponse> criar(@Valid @RequestBody PedidoRequest request) {
        var pedido = pedidoService.criar(request);
        var response = PedidoResponse.de(pedido);
        return ResponseEntity.created(URI.create("/api/pedidos/" + pedido.getId())).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PedidoResponse> buscarPorId(@PathVariable("id") UUID id) {
        var pedido = pedidoService.buscarPorId(id);
        return ResponseEntity.ok(PedidoResponse.de(pedido));
    }
}
