package io.github.luisfm44.caseware.worker.execution.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OperationIdTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t")
    void everyOperationIdentityFieldIsRequired(String missing) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationId(missing, "engagement", "publication", "revision"))
                .withMessage("firmId must be non-blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationId("firm", missing, "publication", "revision"))
                .withMessage("engagementId must be non-blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationId("firm", "engagement", missing, "revision"))
                .withMessage("publicationId must be non-blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationId("firm", "engagement", "publication", missing))
                .withMessage("engagementRevision must be non-blank");
    }
}
