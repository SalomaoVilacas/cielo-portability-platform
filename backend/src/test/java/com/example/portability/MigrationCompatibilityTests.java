package com.example.portability;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationCompatibilityTests {

    @Test
    void upgradingKeepsHistoricalOperationsAndEvidenceUnknown() {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:legacy-migration;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        Flyway.configure().dataSource(source).target("1").load().migrate();
        var database = new JdbcTemplate(source);
        database.update("INSERT INTO beneficiary_consent VALUES('consent','B','C')");
        database.update(
            "INSERT INTO operation(id,idem_key,payload,beneficiary,status,next_attempt,created_at) VALUES('old','key','{}','B','CONFIRMADA',0,'2026-01-01T00:00:00Z')"
        );
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(
            "CONFIRMADA",
            database.queryForObject("SELECT status FROM operation WHERE id='old'", String.class)
        );
        assertNull(
            database.queryForObject(
                "SELECT installment_snapshot FROM operation WHERE id='old'",
                String.class
            )
        );
        assertNull(
            database.queryForObject(
                "SELECT captured_at FROM beneficiary_consent WHERE id='consent'",
                String.class
            )
        );
    }
}
