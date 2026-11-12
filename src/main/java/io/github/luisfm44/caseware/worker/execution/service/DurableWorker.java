package io.github.luisfm44.caseware.worker.execution.service;

import java.io.IOException;
import java.time.Duration;

import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Outcome;
import io.github.luisfm44.caseware.worker.execution.port.JobQueue;
import io.github.luisfm44.caseware.worker.execution.port.JobQueue.Claim;

/** Consumes reserved attempts and reconciles uncertain downstream outcomes. */
public final class DurableWorker {

    private static final System.Logger LOGGER = System.getLogger(DurableWorker.class.getName());

    private final JobQueue jobs;
    private final AttemptTransport transport;
    private final Duration idleDelay;

    public DurableWorker(JobQueue jobs, AttemptTransport transport, Duration idleDelay) {
        this.jobs = jobs;
        this.transport = transport;
        this.idleDelay = idleDelay;
    }

    public void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var claim = jobs.claimReady();
                if (claim.isPresent()) {
                    process(claim.get());
                } else {
                    Thread.sleep(idleDelay);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void process(Claim claim) throws InterruptedException {
        var attempt = claim.attemptId();

        AttemptTransport.Reply reply;
        try {
            reply = claim.reconciling()
                    ? transport.resolve(claim.operationId(), attempt)
                    : transport.submit(claim.operationId(), attempt);
        } catch (IOException unavailable) {
            if (claim.pollCount() == 0) {
                LOGGER.log(System.Logger.Level.WARNING, "Downstream outcome is uncertain; will reconcile", unavailable);
            }
            jobs.defer(claim, backoff(claim.pollCount()));
            return;
        }

        if (reply.outcome() == Outcome.PENDING) {
            jobs.defer(claim, delay(claim.pollCount(), reply.retryAfter()));
        } else if (!jobs.settle(claim, attempt, reply.outcome(), delay(claim.submissions() - 1, reply.retryAfter()))) {
            LOGGER.log(System.Logger.Level.INFO, "Discarded confirmed outcome for attempt {0} after losing its claim",
                    attempt);
        }
    }

    private static Duration delay(int previousAttempts, Duration retryAfter) {
        var backoff = backoff(previousAttempts);
        return retryAfter.compareTo(backoff) > 0 ? retryAfter : backoff;
    }

    private static Duration backoff(int previousAttempts) {
        return Duration.ofMillis(100L << Math.min(previousAttempts, 5));
    }
}
