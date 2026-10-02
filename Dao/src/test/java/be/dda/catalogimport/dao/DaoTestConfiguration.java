package be.dda.catalogimport.dao;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Haalt ook de JdbcTemplate-DAO's ({@code @Repository}) binnen, die {@code @DataJpaTest} zelf niet meeneemt. */
@Configuration(proxyBeanMethods = false)
@ComponentScan("be.dda.catalogimport.dao")
class DaoTestConfiguration {
}
