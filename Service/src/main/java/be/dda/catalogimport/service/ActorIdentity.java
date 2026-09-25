package be.dda.catalogimport.service;

/**
 * Wie een actie ondertekent (Fase 5-AUTH, ontwerp {@code docs/design/fase5-auth-design.md} par. 3): de
 * gebruikersnaam die in de bestaande {@code *_by}-kolommen komt, en het OIDC-subject ({@code sub}) als
 * geverifieerde identiteit.
 * <p>
 * Bewust een kaal record zonder Spring-afhankelijkheid: de Service blijft los van Spring Security. De
 * Web-laag ({@code CurrentActor}) bouwt het uit de aangemelde gebruiker; directe Service-aanroepen zonder
 * login (tests, {@code DemoDataSeeder}) gebruiken {@link #unverified(String)}.
 * <p>
 * {@code subject} is {@code null} = "geen geverifieerde identiteit"; anders niet blanco en hoogstens
 * {@value #MAX_SUBJECT_LENGTH} tekens (bovengrens van {@code sub}, OIDC Core par. 2). {@code username}
 * wordt hier niet gevalideerd: de services passen er hun bestaande naamregels op toe, zodat een
 * ongeldige naam dezelfde fout geeft als vandaag.
 */
public record ActorIdentity(String username, String subject) {

    public static final int MAX_SUBJECT_LENGTH = 255;

    public ActorIdentity {
        if (subject != null && (subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH)) {
            throw new IllegalArgumentException("Actor subject must not be blank or exceed "
                    + MAX_SUBJECT_LENGTH + " characters");
        }
    }

    /** Een naam zonder geverifieerde identiteit ({@code subject = null}). */
    public static ActorIdentity unverified(String username) {
        return new ActorIdentity(username, null);
    }
}
