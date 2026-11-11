package io.github.luisfm44.caseware.worker.demo;

import java.util.List;
import java.util.Optional;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.planning.FanoutStore.Checkpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class DemoRunnerTest {

    private static final OperationId JOB = new OperationId("demo-firm", "engagement-1",
            "demo-publication", "revision-1");
    private static final OperationId DIFFERENT_REVISION = new OperationId("demo-firm", "engagement-1",
            "demo-publication", "revision-2");

    @Test
    void requiresAReportPathBeforeConnectingToPostgres() {
        var runner = new DemoRunner(new MockEnvironment());

        assertThatIllegalStateException().isThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .withMessageContaining("demo.report");
    }

    @Test
    void rejectsAStaleCheckpointBeforeProceeding() {
        var stale = new Checkpoint(1, "2", false);
        var expected = new Checkpoint(4, null, true);

        assertThatIllegalStateException().isThrownBy(() -> DemoRunner.verifyCheckpoint(stale, expected))
                .withMessageContaining("Expected demo checkpoint", "pagesCommitted=4", "pagesCommitted=1");
    }

    @Test
    void rejectsMissingJobsAndIncorrectOperationIds() {
        assertThatIllegalStateException().isThrownBy(() -> DemoRunner.verifyJobs(List.of(), List.of(JOB)))
                .withMessageContaining("Expected demo jobs", "but found []");
        assertThatIllegalStateException()
                .isThrownBy(() -> DemoRunner.verifyJobs(List.of(DIFFERENT_REVISION), List.of(JOB)))
                .withMessageContaining("Expected demo jobs", "revision-1", "revision-2");
    }

    @Test
    void rejectsAReportWithAnUnexpectedNumberOfDispatches() {
        var duplicateDispatch = new DemoRunner.Report(7, 7, 0, 3, 3, 4, 7, 8, 1);

        assertThatIllegalStateException().isThrownBy(() -> DemoRunner.verifyReport(duplicateDispatch))
                .withMessageContaining("Expected demo report", "\"dispatchAttempts\": 7", "\"dispatchAttempts\": 8");
    }

    @Test
    void abortsAnIdleBatchIfAdmissionRejectsTheFirstJob() {
        var downstream = new SimulatedDownstream(JOB);

        assertThatIllegalStateException()
                .isThrownBy(() -> DemoRunner.executeJobs(List.of(JOB), downstream, ignored -> Optional.empty()))
                .withMessage("No demo job could acquire a permit");
        assertThat(downstream.dispatchAttempts()).isZero();
    }
}
