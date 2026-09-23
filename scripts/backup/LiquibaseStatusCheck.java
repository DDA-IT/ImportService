// Kleine, losstaande helperklasse voor restore-and-verify.sh / .ps1.
//
// Waarom dit bestaat: dit project heeft geen liquibase-maven-plugin (enkel de liquibase-core
// runtime-dependency die Spring Boot bij het opstarten gebruikt, zie Web/pom.xml en
// application.yml `spring.liquibase.change-log`). Er is dus geen kant-en-klaar Maven-commando
// om Liquibase-status te controleren tegen een willekeurige (scratch-)database van buiten de
// applicatie. In plaats van een nieuwe Maven-plugin toe te voegen (zou Web/pom.xml wijzigen,
// buiten de scope van dit back-up-/hersteltaakontwerp) hergebruikt dit script rechtstreeks de
// programmatische Liquibase-API die het project al als dependency heeft: de klasse wordt on the
// fly gecompileerd tegen de classpath die `mvn -pl Web -am dependency:build-classpath` oplevert
// (dezelfde liquibase-core-versie en dezelfde postgresql-driver als de applicatie zelf gebruikt).
//
// Gebruik (zie restore-and-verify.sh voor het volledige commando):
//   javac -cp <classpath> LiquibaseStatusCheck.java
//   java  -cp .:<classpath> LiquibaseStatusCheck <jdbcUrl> <user> <password> <resourcesRoot> <changelogPad>
//
// Exitcode 0 = up to date (geen pending changesets), exitcode 1 = pending changesets of fout.

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

public class LiquibaseStatusCheck {

    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("Gebruik: LiquibaseStatusCheck <jdbcUrl> <user> <password> <resourcesRoot> <changelogPad>");
            System.exit(1);
        }
        String jdbcUrl = args[0];
        String user = args[1];
        String password = args[2];
        File resourcesRoot = new File(args[3]);
        String changelogPath = args[4];

        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(changelogPath,
                    new DirectoryResourceAccessor(resourcesRoot), database)) {
                List<ChangeSet> pending = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                if (pending.isEmpty()) {
                    System.out.println("LIQUIBASE_STATUS=UP_TO_DATE");
                    System.exit(0);
                } else {
                    System.out.println("LIQUIBASE_STATUS=PENDING count=" + pending.size());
                    for (ChangeSet changeSet : pending) {
                        System.out.println("  pending: " + changeSet.toString(false));
                    }
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("LIQUIBASE_STATUS=ERROR " + e);
            System.exit(1);
        }
    }
}
