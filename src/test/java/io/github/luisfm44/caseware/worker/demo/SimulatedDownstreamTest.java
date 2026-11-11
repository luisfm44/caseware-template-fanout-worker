package io.github.luisfm44.caseware.worker.demo;

import java.util.concurrent.TimeoutException;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.DownstreamTransport.Outcome;
import io.github.luisfm44.caseware.worker.execution.port.DownstreamTransport.Reply;
import io.github.luisfm44.caseware.worker.execution.port.DownstreamTransport.Resolution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedDownstreamTest {

    private static final OperationId LOST_REPLY = new OperationId("demo-firm", "engagement-1",
            "demo-publication", "revision-1");
    private static final OperationId OTHER = new OperationId("demo-firm", "engagement-2",
            "demo-publication", "revision-1");

    @Test
    void coalescesAnInFlightReplayAndConfirmsTheEffectAfterLosingItsReply() {
        var downstream = new SimulatedDownstream(LOST_REPLY);

        assertThat(downstream.reconcile(LOST_REPLY).toCompletableFuture().join())
                .isEqualTo(Resolution.NOT_APPLIED);
        var first = downstream.submit(LOST_REPLY).toCompletableFuture();
        var replay = downstream.submit(LOST_REPLY).toCompletableFuture();
        var pending = downstream.reconcile(LOST_REPLY).toCompletableFuture();
        var other = downstream.submit(OTHER).toCompletableFuture();

        assertThat(first).isNotDone();
        assertThat(replay).isNotDone();
        assertThat(pending).isNotDone();
        assertThat(other).isNotDone();
        assertThat(downstream.peakInFlight()).isEqualTo(2);
        assertThat(downstream.appliedEffects()).isZero();

        downstream.completeBatch();

        assertThatThrownBy(first::join).hasCauseInstanceOf(TimeoutException.class);
        assertThatThrownBy(replay::join).hasCauseInstanceOf(TimeoutException.class);
        assertThat(pending.join()).isEqualTo(Resolution.APPLIED);
        assertThat(other.join()).isEqualTo(new Reply(Outcome.APPLIED));
        assertThat(downstream.reconcile(LOST_REPLY).toCompletableFuture().join())
                .isEqualTo(Resolution.APPLIED);
        assertThat(downstream.submit(LOST_REPLY).toCompletableFuture().join())
                .isEqualTo(new Reply(Outcome.APPLIED));
        downstream.completeBatch();

        assertThat(downstream.dispatchAttempts()).isEqualTo(4);
        assertThat(downstream.reconciliations()).isEqualTo(3);
        assertThat(downstream.appliedEffects()).isEqualTo(2);
        assertThat(downstream.duplicateEffects()).isZero();
    }
}
