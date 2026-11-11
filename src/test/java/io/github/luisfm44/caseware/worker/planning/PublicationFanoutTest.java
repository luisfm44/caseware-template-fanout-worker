package io.github.luisfm44.caseware.worker.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PublicationFanoutTest {

    @ParameterizedTest
    @CsvSource(value = {"NULL, publication-a", "' ', publication-a", "firm-a, NULL", "firm-a, ' '"}, nullValues = "NULL")
    void rejectsMissingFirmOrPublicationIds(String firmId, String publicationId) {
        assertThatThrownBy(() -> new Publication(firmId, publicationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("firmId and publicationId must be non-blank");
    }

    @Test
    void rejectsInvalidPageSizeBeforeAccessingTheSourceOrStore() {
        var source = mock(EngagementSource.class);
        var store = mock(FanoutStore.class);

        assertThatThrownBy(() -> new PublicationFanout(source, store, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pageSize must be positive");

        verifyNoInteractions(source, store);
    }
}
