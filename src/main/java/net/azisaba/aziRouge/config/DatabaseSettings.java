package net.azisaba.aziRouge.config;

public record DatabaseSettings(
        boolean enabled,
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        int maximumPoolSize,
        long connectionTimeoutMillis
) {
    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useSsl=" + useSsl
                + "&tcpKeepAlive=true"
                + "&useUnicode=true"
                + "&characterEncoding=utf8";
    }
}
