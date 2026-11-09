package com.example.portability.operation;

import com.example.portability.integration.*;
import com.example.portability.operation.OperationModels.StoredOperation;
import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OperationWorker {

    private static final Logger LOG = LoggerFactory.getLogger(OperationWorker.class);
    private final OperationStore store;
    private final CentralPort central;
    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final long retryBase;
    private final MeterRegistry metrics;

    public OperationWorker(
        OperationStore store,
        CentralPort central,
        TransactionTemplate transactions,
        Environment environment,
        MeterRegistry metrics
    ) {
        this.store = store;
        this.central = central;
        this.transactions = transactions;
        this.metrics = metrics;
        maxAttempts = environment.getProperty("integration.max-attempts", Integer.class, 5);
        retryBase = environment.getProperty("integration.retry-base-ms", Long.class, 1000L);
        Gauge.builder("portability.pending", store, OperationStore::pendingCount).register(metrics);
        Gauge.builder(
            "portability.pending.oldest.seconds",
            store,
            OperationStore::oldestPendingSeconds
        ).register(metrics);
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        for (StoredOperation operation : store.due()) {
            try {
                process(operation);
            } catch (RuntimeException failure) {
                LOG.error(
                    "event=operation_unexpected_failure operationId={} protocol={} attempt={}",
                    operation.id(),
                    operation.protocol(),
                    operation.attempts(),
                    failure
                );
                try {
                    failed(
                        operation,
                        new IntegrationFailure(
                            "Falha interna no processamento; consulte os logs",
                            true
                        )
                    );
                } catch (RuntimeException persistenceFailure) {
                    LOG.error(
                        "event=operation_failure_persistence operationId={}",
                        operation.id(),
                        persistenceFailure
                    );
                }
            }
        }
    }

    private void process(StoredOperation operation) {
        long started = System.nanoTime();
        try {
            if (operation.protocol() == null) {
                String protocol = central.submit(operation.key(), operation.request());
                transactions.executeWithoutResult(transaction ->
                    store.accepted(operation.id(), protocol)
                );
            } else {
                var poll = central.poll(operation.protocol());
                if (
                    poll.result() == null || "PENDENTE".equals(poll.result().status())
                ) transactions.executeWithoutResult(transaction ->
                    store.schedule(operation.id(), poll.retryAfterMillis())
                );
                else {
                    OperationStatus status = OperationStatus.valueOf(poll.result().status());
                    transactions.executeWithoutResult(transaction ->
                        store.complete(operation.id(), status, poll.result().motivoRejeicao())
                    );
                    metrics.counter("portability.completed", "status", status.name()).increment();
                }
            }
            LOG.info(
                "event=operation_processed operationId={} protocol={} attempt={} durationMs={}",
                operation.id(),
                operation.protocol(),
                operation.attempts(),
                (System.nanoTime() - started) / 1000000
            );
        } catch (IntegrationFailure failure) {
            failed(operation, failure);
        } finally {
            metrics
                .timer("portability.integration.duration")
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void failed(StoredOperation operation, IntegrationFailure failure) {
        int attempts = operation.attempts() + 1;
        boolean stop = attempts >= maxAttempts || !failure.retryable();
        long delay = Math.min(30000, retryBase * (1L << Math.min(attempts - 1, 10)));
        transactions.executeWithoutResult(transaction ->
            store.failed(operation, attempts, stop, failure.getMessage(), delay)
        );
        metrics
            .counter("portability.integration.failures", "outcome", stop ? "exhausted" : "retry")
            .increment();
        LOG.warn(
            "event=operation_retry operationId={} protocol={} attempt={} nextDelayMs={} exhausted={} reason={}",
            operation.id(),
            operation.protocol(),
            attempts,
            delay,
            stop,
            failure.getMessage()
        );
    }
}
