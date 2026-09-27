import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Maakt een leeg PostgreSQL-schema voor een volledige testronde (scripts/test/run-full-tests.ps1).
 *
 * <p>Geen psql nodig: het script draait dit als losse bronfile met alleen de JDBC-driver op het classpath
 * (zelfde patroon als scripts/backup/LiquibaseStatusCheck.java). De databasegebruiker hoeft geen CREATEDB te
 * hebben, enkel CREATE op de database.
 *
 * <p>Argumenten: {@code <jdbc-url> <gebruiker> <wachtwoord> <schema> <reset|create|drop>}. Het wachtwoord komt uit
 * de omgeving van het script en wordt nooit gelogd. {@code public} wordt altijd geweigerd, zodat er nooit
 * per ongeluk echte data wordt weggegooid.
 */
public class ResetTestSchema {

    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("gebruik: ResetTestSchema <jdbc-url> <gebruiker> <wachtwoord> <schema> <reset|create|drop>");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String schema = args[3];
        String action = args[4];
        if (!SCHEMA.matcher(schema).matches() || schema.equals("public") || schema.startsWith("pg_")
                || schema.equals("information_schema")) {
            System.err.println("FOUT: schemanaam '" + schema + "' is niet toegestaan (alleen [a-z][a-z0-9_]*, nooit public).");
            System.exit(2);
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            switch (action) {
                case "reset" -> {
                    statement.execute("drop schema if exists " + schema + " cascade");
                    statement.execute("create schema " + schema);
                }
                case "create" -> statement.execute("create schema if not exists " + schema);
                case "drop" -> statement.execute("drop schema if exists " + schema + " cascade");
                default -> {
                    System.err.println("FOUT: onbekende actie '" + action + "'.");
                    System.exit(2);
                }
            }
            try (ResultSet result = statement.executeQuery(
                    "select (select setting from pg_settings where name = 'max_connections')::int, "
                            + "(select count(*) from pg_stat_activity)")) {
                result.next();
                System.out.println("schema '" + schema + "': " + action + " gelukt; serververbindingen "
                        + result.getInt(2) + " van " + result.getInt(1));
            }
        }
    }
}
