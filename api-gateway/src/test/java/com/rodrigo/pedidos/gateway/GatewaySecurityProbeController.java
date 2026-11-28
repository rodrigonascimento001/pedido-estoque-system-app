package com.rodrigo.pedidos.gateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GatewaySecurityProbeController {

    @GetMapping("/api/pedidos/probe")
    String probe() {
        return "forwarded";
    }

    @PostMapping("/api/usuarios")
    String register() {
        return "created";
    }
}
