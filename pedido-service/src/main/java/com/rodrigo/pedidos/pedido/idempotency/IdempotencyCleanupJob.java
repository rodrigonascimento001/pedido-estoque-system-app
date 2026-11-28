package com.rodrigo.pedidos.pedido.idempotency;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Remove periodicamente, em lotes, as chaves cuja retenção terminou. */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyStore store;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public IdempotencyCleanupJob(IdempotencyStore store, TransactionTemplate transactionTemplate,
                                 @Value("${app.idempotency.cleanup-batch-size:500}") int batchSize) {
        this.store = store;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-delay-ms:600000}")
    public void limpar() {
        int removidas;
        int total = 0;
        do {
            removidas = transactionTemplate.execute(status -> store.removerExpiradas(Instant.now(), batchSize));
            total += removidas;
        } while (removidas == batchSize);
        if (total > 0) {
            log.info("Chaves de idempotência expiradas removidas: {}", total);
        }
    }
}
