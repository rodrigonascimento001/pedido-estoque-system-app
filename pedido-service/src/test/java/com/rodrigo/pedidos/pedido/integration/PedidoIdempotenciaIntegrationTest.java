package com.rodrigo.pedidos.pedido.integration;

import com.rodrigo.pedidos.comum.security.AuthenticatedUserHeader;
import com.rodrigo.pedidos.pedido.dto.PedidoRequest;
import com.rodrigo.pedidos.pedido.dto.PedidoResponse;
import com.rodrigo.pedidos.pedido.idempotency.IdempotencyCleanupJob;
import com.rodrigo.pedidos.pedido.idempotency.IdempotencyKeys;
import com.rodrigo.pedidos.pedido.repository.PedidoRepository;
import com.rodrigo.pedidos.pedido.service.PedidoService;
import com.rodrigo.pedidos.pedido.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class PedidoIdempotenciaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PedidoRepository pedidoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PedidoService pedidoService;

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static PedidoRequest pedido(int quantidade, double preco) {
        return new PedidoRequest(List.of(
                new PedidoRequest.ItemRequest(UUID.randomUUID(), quantidade, BigDecimal.valueOf(preco))));
    }

    private ResponseEntity<PedidoResponse> post(PedidoRequest request, String chave, String usuario) {
        var headers = new HttpHeaders();
        if (chave != null) {
            headers.set(IdempotencyKeys.HEADER, chave);
        }
        if (usuario != null) {
            headers.set(AuthenticatedUserHeader.NAME, usuario);
        }
        return restTemplate.exchange("/api/pedidos", HttpMethod.POST,
                new HttpEntity<>(request, headers), PedidoResponse.class);
    }

    private ResponseEntity<String> postRaw(PedidoRequest request, String chave, String usuario) {
        var headers = new HttpHeaders();
        if (chave != null) {
            headers.set(IdempotencyKeys.HEADER, chave);
        }
        if (usuario != null) {
            headers.set(AuthenticatedUserHeader.NAME, usuario);
        }
        return restTemplate.exchange("/api/pedidos", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);
    }

    private static String novaChave() {
        return UUID.randomUUID().toString();
    }

    private int eventosDoPedido(UUID pedidoId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE message_key = ?", Integer.class, pedidoId.toString());
    }

    @Test
    void primeiraRequisicaoComChaveCriaOPedidoERegistraAChave() {
        var chave = novaChave();

        var resposta = post(pedido(1, 10), chave, "user-a");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getHeaders().containsKey(IdempotencyKeys.REPLAY_HEADER)).isFalse();
        var pedidoId = resposta.getBody().id();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pedido_id FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                UUID.class, "user-a", chave)).isEqualTo(pedidoId);
    }

    @Test
    void repeticaoDevolveOPedidoOriginalSemCriarOutro() {
        var chave = novaChave();
        var request = pedido(2, 19.90);
        var primeira = post(request, chave, "user-a");
        long pedidosAntes = pedidoRepository.count();
        double repeticoesAntes = meterRegistry.counter("pedido.idempotency.replay").count();

        var segunda = post(request, chave, "user-a");

        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getBody().id()).isEqualTo(primeira.getBody().id());
        assertThat(segunda.getHeaders().getLocation()).isEqualTo(primeira.getHeaders().getLocation());
        assertThat(segunda.getHeaders().getFirst(IdempotencyKeys.REPLAY_HEADER)).isEqualTo("true");
        assertThat(pedidoRepository.count()).isEqualTo(pedidosAntes);
        assertThat(eventosDoPedido(primeira.getBody().id())).isEqualTo(1);
        assertThat(meterRegistry.counter("pedido.idempotency.replay").count()).isEqualTo(repeticoesAntes + 1);
    }

    @Test
    void valoresEquivalentesNoPrecoContamComoOMesmoCorpo() {
        var chave = novaChave();
        var produtoId = UUID.randomUUID();
        var primeira = post(new PedidoRequest(List.of(
                new PedidoRequest.ItemRequest(produtoId, 1, new BigDecimal("10")))), chave, "user-a");

        var segunda = post(new PedidoRequest(List.of(
                new PedidoRequest.ItemRequest(produtoId, 1, new BigDecimal("10.00")))), chave, "user-a");

        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getBody().id()).isEqualTo(primeira.getBody().id());
    }

    @Test
    void mesmaChaveComCorpoDiferenteRetorna422SemCriarPedido() {
        var chave = novaChave();
        post(pedido(1, 10), chave, "user-a");
        long pedidosAntes = pedidoRepository.count();
        double conflitosAntes = meterRegistry.counter("pedido.idempotency.conflict").count();

        var resposta = postRaw(pedido(5, 99), chave, "user-a");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resposta.getBody()).doesNotContain(chave);
        assertThat(pedidoRepository.count()).isEqualTo(pedidosAntes);
        assertThat(meterRegistry.counter("pedido.idempotency.conflict").count()).isEqualTo(conflitosAntes + 1);
    }

    @Test
    void semChaveCadaRequisicaoCriaUmNovoPedido() {
        var request = pedido(1, 10);

        var primeira = post(request, null, "user-a");
        var segunda = post(request, null, "user-a");

        assertThat(primeira.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getBody().id()).isNotEqualTo(primeira.getBody().id());
    }

    @Test
    void chaveInvalidaOuSemUsuarioRetorna400SemCriarPedido() {
        long pedidosAntes = pedidoRepository.count();

        assertThat(postRaw(pedido(1, 10), "com espaco", "user-a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw(pedido(1, 10), "x".repeat(129), "user-a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw(pedido(1, 10), "ação", "user-a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw(pedido(1, 10), "", "user-a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw(pedido(1, 10), novaChave(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pedidoRepository.count()).isEqualTo(pedidosAntes);
    }

    @Test
    void usuariosDiferentesComAMesmaChaveRecebemPedidosDiferentes() {
        var chave = novaChave();
        var request = pedido(1, 10);

        var deA = post(request, chave, "user-a");
        var deB = post(request, chave, "user-b");

        assertThat(deA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deB.getBody().id()).isNotEqualTo(deA.getBody().id());
        assertThat(deB.getHeaders().containsKey(IdempotencyKeys.REPLAY_HEADER)).isFalse();
    }

    @Test
    void requisicoesConcorrentesComAMesmaChaveCriamUmUnicoPedido() throws Exception {
        var chave = novaChave();
        var request = pedido(1, 10);
        int threads = 8;
        var executor = Executors.newFixedThreadPool(threads);
        var largada = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<PedidoResponse>>> futuros = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<ResponseEntity<PedidoResponse>> tarefa = () -> {
                    largada.await();
                    return post(request, chave, "user-concorrente");
                };
                futuros.add(executor.submit(tarefa));
            }
            largada.countDown();

            var ids = new ArrayList<UUID>();
            for (var futuro : futuros) {
                var resposta = futuro.get();
                assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                ids.add(resposta.getBody().id());
            }

            assertThat(ids).hasSize(threads).containsOnly(ids.get(0));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                    Integer.class, "user-concorrente", chave)).isEqualTo(1);
            assertThat(eventosDoPedido(ids.get(0))).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void falhaNaCriacaoNaoConsomeAChave() {
        var chave = novaChave();
        long pedidosAntes = pedidoRepository.count();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            pedidoService.criar(pedido(1, 10), chave, "user-a");
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                Integer.class, "user-a", chave)).isZero();
        assertThat(pedidoRepository.count()).isEqualTo(pedidosAntes);

        var resposta = post(pedido(1, 10), chave, "user-a");
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.getHeaders().containsKey(IdempotencyKeys.REPLAY_HEADER)).isFalse();
    }

    @Test
    void chaveExpiradaEhTratadaComoNovaECriaOutroPedido() {
        var chave = novaChave();
        var request = pedido(1, 10);
        var primeira = post(request, chave, "user-a");
        jdbcTemplate.update("UPDATE idempotency_key SET expires_at = now() - interval '1 minute' "
                + "WHERE scope = ? AND idem_key = ?", "user-a", chave);

        var segunda = post(request, chave, "user-a");

        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getBody().id()).isNotEqualTo(primeira.getBody().id());
        assertThat(segunda.getHeaders().containsKey(IdempotencyKeys.REPLAY_HEADER)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pedido_id FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                UUID.class, "user-a", chave)).isEqualTo(segunda.getBody().id());
    }

    @Test
    void limpezaRemoveSomenteChavesExpiradas() {
        var vencida = novaChave();
        var vigente = novaChave();
        post(pedido(1, 10), vencida, "user-limpeza");
        post(pedido(1, 10), vigente, "user-limpeza");
        jdbcTemplate.update("UPDATE idempotency_key SET expires_at = now() - interval '1 minute' "
                + "WHERE scope = ? AND idem_key = ?", "user-limpeza", vencida);

        cleanupJob.limpar();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                Integer.class, "user-limpeza", vencida)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                Integer.class, "user-limpeza", vigente)).isEqualTo(1);
    }
}
