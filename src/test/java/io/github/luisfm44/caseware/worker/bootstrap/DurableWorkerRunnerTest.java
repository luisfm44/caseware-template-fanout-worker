package io.github.luisfm44.caseware.worker.bootstrap;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.github.luisfm44.caseware.worker.adapter.postgres.PostgresJobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class DurableWorkerRunnerTest {

    @Test
    void startsTheConfiguredRunnerAndPreservesInterruptStatus() {
        var configuration = new MockEnvironment()
                .withProperty("worker.capacity", "2")
                .withProperty("FANOUT_DB_PASSWORD", "test")
                .withProperty("FANOUT_DOWNSTREAM_URL", "http://127.0.0.1:1");
        try (var queues = mockConstruction(PostgresJobQueue.class, (queue, context) -> {
            assertThat(context.arguments()).hasSize(3);
            assertThat(context.arguments().subList(1, 3)).isEqualTo(List.of(2, Duration.ofSeconds(2)));
            when(queue.claimReady()).thenAnswer(ignored -> {
                Thread.currentThread().interrupt();
                return Optional.empty();
            });
        })) {
            new DurableWorkerRunner(configuration).run(null);

            assertThat(Thread.interrupted()).isTrue();
            assertThat(queues.constructed()).hasSize(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsNonPositiveTimingConfigurationBeforeConnecting() {
        var configuration = new MockEnvironment()
                .withProperty("worker.capacity", "1")
                .withProperty("worker.idle-ms", "0");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new DurableWorkerRunner(configuration).run(null));

        assertThat(failure).hasMessage("worker.idle-ms must be positive");
    }
}
