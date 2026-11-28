package com.rodrigo.pedidos.pedido.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.rodrigo.pedidos.pedido.dto.PedidoRequest;

/** Validação do formato da chave e impressão digital do corpo da requisição. */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private static final Pattern FORMATO = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

    private IdempotencyKeys() {
    }

    public static void validarChave(String chave) {
        if (chave == null || !FORMATO.matcher(chave).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key deve ter de 1 a 128 caracteres ASCII imprimíveis, sem espaços");
        }
    }

    /**
     * SHA-256 de uma forma canônica do pedido: a ordem dos itens conta, e valores equivalentes como
     * 10 e 10.00 geram o mesmo hash.
     */
    public static String impressaoDigital(PedidoRequest request) {
        var canonico = new StringBuilder();
        for (var item : request.itens()) {
            canonico.append(item.produtoId()).append('|')
                    .append(item.quantidade()).append('|')
                    .append(normalizar(item.precoUnitario())).append('\n');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonico.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private static String normalizar(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }
}
