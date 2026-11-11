package io.github.luisfm44.caseware.worker.adapter.memory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InMemoryAdmissionControlTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void admissionCapacityMustBePositive(int capacity) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InMemoryAdmissionControl(capacity))
                .withMessage("capacity must be positive");
    }
}
