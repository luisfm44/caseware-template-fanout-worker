package io.github.luisfm44.caseware.worker.execution.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Outcome;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Reply;
import io.github.luisfm44.caseware.worker.execution.port.JobQueue;
import io.github.luisfm44.caseware.worker.execution.port.JobQueue.Claim;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableWorkerTest {

    private static final OperationId OPERATION = new OperationId("firm", "engagement", "publication", "revision");
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final Duration IDLE_DELAY = Duration.ofMillis(25);

    private final JobQueue jobs = mock(JobQueue.class);
    private final AttemptTransport transport = mock(AttemptTransport.class);
    private final DurableWorker worker = new DurableWorker(jobs, transport, IDLE_DELAY);

    @Test
    void settlesAnAuthorizedSubmissionAndDefersARecoveredPendingAttempt() throws Exception {
        var fresh = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 0, false);
        var recovering = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 1, true);
        when(transport.submit(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.APPLIED, Duration.ZERO));
        when(transport.resolve(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.PENDING, Duration.ofSeconds(2)));

        worker.process(fresh);
        worker.process(recovering);

        verify(jobs).settle(fresh, ATTEMPT, Outcome.APPLIED, Duration.ofMillis(100));
        verify(jobs).defer(recovering, Duration.ofSeconds(2));
    }

    @Test
    void settlesAuthoritativeNoEffectWithSubmissionBackoff() throws Exception {
        var claim = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 3, 10, true);
        when(transport.resolve(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.NOT_APPLIED, Duration.ZERO));

        worker.process(claim);

        verify(jobs).settle(claim, ATTEMPT, Outcome.NOT_APPLIED, Duration.ofMillis(400));
    }

    @Test
    void defersFailedSubmissionsAndCapsStatusBackoff() throws Exception {
        var fresh = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 0, false);
        var recovering = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 20, true);
        when(transport.submit(OPERATION, ATTEMPT)).thenThrow(new IOException("lost reply"));
        when(transport.resolve(OPERATION, ATTEMPT)).thenThrow(new IOException("status unavailable"));

        worker.process(fresh);
        worker.process(recovering);

        verify(jobs).defer(fresh, Duration.ofMillis(100));
        verify(jobs).defer(recovering, Duration.ofMillis(3_200));
    }

    @Test
    void waitsForWorkAndStopsWhenInterrupted() throws Exception {
        var claim = new Claim(OPERATION, CLAIM_TOKEN, ATTEMPT, 1, 0, true);
        when(jobs.claimReady()).thenReturn(Optional.empty()).thenReturn(Optional.of(claim));
        when(transport.resolve(OPERATION, ATTEMPT)).thenReturn(new Reply(Outcome.APPLIED, Duration.ZERO));
        doAnswer(ignored -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(jobs).settle(claim, ATTEMPT, Outcome.APPLIED, Duration.ofMillis(100));

        try {
            worker.consume();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
