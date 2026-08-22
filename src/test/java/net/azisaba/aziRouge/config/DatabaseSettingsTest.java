package net.azisaba.aziRouge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseSettingsTest {
    @Test
    void buildsMariaDbUrlWithExplicitTransportOptions() {
        DatabaseSettings settings = new DatabaseSettings(
                true,
                "db.internal",
                3307,
                "azirouge_test",
                "user",
                "secret",
                true,
                4,
                5_000L
        );

        assertEquals(
                "jdbc:mariadb://db.internal:3307/azirouge_test"
                        + "?useSsl=true&tcpKeepAlive=true&useUnicode=true&characterEncoding=utf8",
                settings.jdbcUrl()
        );
    }
}
