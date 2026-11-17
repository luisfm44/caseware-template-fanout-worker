package io.github.luisfm44.caseware.worker.adapter.postgres;

import java.lang.reflect.Modifier;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywaySchemaTest {

    @Test
    void rejectsNullDataSource() {
        assertThatThrownBy(() -> FlywaySchema.migrate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dataSource");
    }

    @Test
    void preservesSchemaLookupFailure() throws SQLException {
        var source = mock(DataSource.class);
        var failure = new SQLException("connection unavailable");
        when(source.getConnection()).thenThrow(failure);

        assertThatThrownBy(() -> FlywaySchema.migrate(source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot determine PostgreSQL schema for Flyway")
                .hasCause(failure);
    }

    @Test
    void refusesUnknownAndBlankSchemas() throws SQLException {
        var source = mock(DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getSchema()).thenReturn(null, " ");

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> FlywaySchema.migrate(source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Configure an existing PostgreSQL currentSchema before Flyway migration");
        }
    }

    @Test
    void hasPrivateUtilityConstructor() throws ReflectiveOperationException {
        var constructor = FlywaySchema.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isInstanceOf(FlywaySchema.class);
    }
}
