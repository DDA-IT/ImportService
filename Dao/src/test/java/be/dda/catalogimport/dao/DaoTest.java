package be.dda.catalogimport.dao;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Meta-annotatie voor alle Dao-tests: {@code @DataJpaTest} tegen de echte PostgreSQL (geen H2, geen
 * Testcontainers), transactioneel terugrollend. Alle tests gebruiken dezelfde configuratie en delen dus
 * één Spring-context (en één verbindingspool).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DaoTestConfiguration.class)
public @interface DaoTest {
}
