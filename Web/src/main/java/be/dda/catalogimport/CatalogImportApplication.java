package be.dda.catalogimport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@SpringBootApplication
public class CatalogImportApplication {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogImportApplication.class);

    public static void main(String[] args) {
        Environment env = SpringApplication.run(CatalogImportApplication.class, args).getEnvironment();
        logApplicationStartup(env);
    }

    /**
     * Logt bij opstart de lokale en externe URL's van de applicatie, samen met de actieve profielen
     * (of de default-profielen als er geen actief profiel is).
     */
    private static void logApplicationStartup(Environment env) {
        String protocol = env.getProperty("server.ssl.key-store") != null ? "https" : "http";
        String applicationName = env.getProperty("spring.application.name");
        String serverPort = env.getProperty("local.server.port", env.getProperty("server.port"));
        String configuredContextPath = env.getProperty("server.servlet.context-path");
        String contextPath = StringUtils.hasText(configuredContextPath) ? configuredContextPath : "/";
        String hostAddress;
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            LOG.warn("Het lokale adres kon niet worden bepaald, 'localhost' wordt gebruikt");
            hostAddress = "localhost";
        }
        String[] profiles = env.getActiveProfiles().length == 0 ? env.getDefaultProfiles() : env.getActiveProfiles();

        LOG.info("""

                ----------------------------------------------------------
                \tApplication '{}' is running! Access URLs:
                \tLocal: \t\t{}://localhost:{}{}
                \tExternal: \t{}://{}:{}{}
                \tProfile(s): \t{}
                ----------------------------------------------------------""",
                applicationName,
                protocol, serverPort, contextPath,
                protocol, hostAddress, serverPort, contextPath,
                Arrays.toString(profiles));
    }
}
