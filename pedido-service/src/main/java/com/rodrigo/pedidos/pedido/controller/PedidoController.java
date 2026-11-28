package com.rodrigo.pedidos.pedido.controller;

import com.rodrigo.pedidos.comum.security.AuthenticatedUserHeader;
import com.rodrigo.pedidos.pedido.domain.Pedido;
import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.idempotency.IdempotencyKeys;
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
    public ResponseEntity<PedidoResponse> criar(
            @Valid @RequestBody PedidoRequest request,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = AuthenticatedUserHeader.NAME, required = false) String usuario) {
        if (idempotencyKey == null) {
            return criado(pedidoService.criar(request), false);
        }
        var criacao = pedidoService.criar(request, idempotencyKey, usuario);
        return criado(criacao.pedido(), criacao.repeticao());
    }

    private ResponseEntity<PedidoResponse> criado(Pedido pedido, boolean repeticao) {
        var resposta = ResponseEntity.created(URI.create("/api/pedidos/" + pedido.getId()));
        if (repeticao) {
            resposta.header(IdempotencyKeys.REPLAY_HEADER, "true");
        }
        return resposta.body(PedidoResponse.de(pedido));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PedidoResponse> buscarPorId(@PathVariable("id") UUID id) {
        var pedido = pedidoService.buscarPorId(id);
        return ResponseEntity.ok(PedidoResponse.de(pedido));
    }
}
