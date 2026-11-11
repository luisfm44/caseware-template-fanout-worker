package io.github.luisfm44.caseware.worker.durable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.luisfm44.caseware.worker.OperationId;
import io.github.luisfm44.caseware.worker.durable.AttemptTransport.Outcome;
import io.github.luisfm44.caseware.worker.durable.AttemptTransport.Reply;
import io.github.luisfm44.caseware.worker.durable.PostgresJobQueue.Claim;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DurableWorkerRunnerTest {

    private static final OperationId OPERATION = new OperationId("firm", "engagement", "publication", "revision");
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final Duration IDLE_DELAY = Duration.ofMillis(25);

    private final PostgresJobQueue jobs = mock(PostgresJobQueue.class);
    private final AttemptTransport transport = mock(AttemptTransport.class);

    @Test
    void defersUnadmittedWorkWithoutCallingDownstream() throws InterruptedException {
        var claim = new Claim(OPERATION, CLAIM_TOKEN, null, 0, 0);
        when(jobs.authorizeSubmission(claim)).thenReturn(Optional.empty());

        DurableWorkerRunner.process(jobs, transport, claim, IDLE_DELAY);

        verify(jobs).defer(claim, IDLE_DELAY);
        verifyNoInteractions(transport);
    }

    @Test
    void settlesAnAuthorizedSubmissionAndDefersARecoveredPendingAttempt() throws Exception {
        var fresh = new Claim(OPERATION, CLAIM_TOKEN, null, 0, 0);
        var recovering = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 1);
        when(jobs.authorizeSubmission(fresh)).thenReturn(Optional.of(ATTEMPT));
        when(transport.submit(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.APPLIED, Duration.ZERO));
        when(transport.resolve(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.PENDING, Duration.ofSeconds(2)));

        DurableWorkerRunner.process(jobs, transport, fresh, IDLE_DELAY);
        DurableWorkerRunner.process(jobs, transport, recovering, IDLE_DELAY);

        verify(jobs).settle(fresh, ATTEMPT, Outcome.APPLIED, Duration.ofMillis(100));
        verify(jobs).defer(recovering, Duration.ofSeconds(2));
    }

    @Test
    void settlesAuthoritativeNoEffectWithSubmissionBackoff() throws Exception {
        var claim = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 3, 10);
        when(transport.resolve(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.NOT_APPLIED, Duration.ZERO));

        DurableWorkerRunner.process(jobs, transport, claim, IDLE_DELAY);

        verify(jobs).settle(claim, ATTEMPT, Outcome.NOT_APPLIED, Duration.ofMillis(400));
    }

    @Test
    void defersFailedSubmissionsAndCapsStatusBackoff() throws Exception {
        var fresh = new Claim(OPERATION, CLAIM_TOKEN, null, 0, 0);
        var recovering = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 20);
        when(jobs.authorizeSubmission(fresh)).thenReturn(Optional.of(ATTEMPT));
        when(transport.submit(OPERATION, ATTEMPT)).thenThrow(new IOException("lost reply"));
        when(transport.resolve(OPERATION, ATTEMPT)).thenThrow(new IOException("status unavailable"));

        DurableWorkerRunner.process(jobs, transport, fresh, IDLE_DELAY);
        DurableWorkerRunner.process(jobs, transport, recovering, IDLE_DELAY);

        verify(jobs).defer(fresh, Duration.ofMillis(100));
        verify(jobs).defer(recovering, Duration.ofMillis(3_200));
    }

    @Test
    void waitsForWorkAndStopsWhenInterrupted() throws Exception {
        var claim = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 0);
        when(jobs.claimReady()).thenReturn(Optional.empty()).thenReturn(Optional.of(claim));
        when(transport.resolve(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.APPLIED, Duration.ZERO));
        doAnswer(ignored -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(jobs).settle(claim, ATTEMPT, Outcome.APPLIED, Duration.ofMillis(100));

        try {
            DurableWorkerRunner.consume(jobs, transport, IDLE_DELAY);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

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
