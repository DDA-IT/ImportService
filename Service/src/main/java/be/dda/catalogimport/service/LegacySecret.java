package be.dda.catalogimport.service;

/**
 * Het legacy-wachtwoord uit een bundel ({@code secret}, contract {@code docs/design/legacy-config-bundle-v1.md} par. 1
 * en 2.1; design par. 2 "Secret door de request", stap 3). {@link #toString()} toont nooit de waarde. Komt nooit in een
 * entiteit, een log, een foutmelding of {@code bundle_json}; vanaf S4 enkel als parameter naar de credentialopslag.
 * In S1 wordt een bundel met secret geweigerd.
 */
public record LegacySecret(String label, String value) {

    @Override
    public String toString() {
        return "LegacySecret[label=" + label + ", value=[REDACTED]]";
    }
}
