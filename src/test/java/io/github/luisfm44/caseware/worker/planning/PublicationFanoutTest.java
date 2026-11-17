package io.github.luisfm44.caseware.worker.planning;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void requiresASnapshotIdEvenOnAnEmptyFinalPage(String snapshotId) {
        assertThatThrownBy(() -> new EngagementSource.Page(List.of(), null, snapshotId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("snapshotId must be non-blank");
    }

    @Test
    void stopsAnUnsafeLegacyResumeWithoutPinnedSnapshot() {
        var publication = new Publication("firm-a", "publication-a");
        var source = mock(EngagementSource.class);
        var store = mock(FanoutStore.class);
        org.mockito.Mockito.when(store.checkpoint(publication))
                .thenReturn(new FanoutStore.Checkpoint(1, "cursor-2", false, null));

        assertThatThrownBy(() -> new PublicationFanout(source, store, 2).plan(publication))
                .isInstanceOf(EngagementSource.SnapshotUnavailableException.class)
                .hasMessage("Cannot resume a checkpoint without a pinned engagement snapshot");
        verifyNoInteractions(source);
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
