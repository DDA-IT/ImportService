package be.dda.catalogimport.dao;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/** Minimale testapplicatie voor de Dao-tests: entiteiten uit Domain, repositories uit dit package. */
@SpringBootApplication
@EntityScan("be.dda.catalogimport.domain")
public class DaoTestApplication {
}
