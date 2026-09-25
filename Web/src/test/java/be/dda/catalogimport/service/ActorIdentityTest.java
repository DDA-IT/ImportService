package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link ActorIdentity} (Fase 5-AUTH, ontwerp par. 3): {@code subject} is {@code null} of 1..255 tekens. */
class ActorIdentityTest {

    @Test
    void anUnverifiedIdentityHasNoSubject() {
        ActorIdentity actor = ActorIdentity.unverified("jan.peeters");

        assertThat(actor.username()).isEqualTo("jan.peeters");
        assertThat(actor.subject()).isNull();
    }

    @Test
    void theUsernameIsNotValidatedHereSoServicesKeepTheirOwnErrors() {
        assertThat(ActorIdentity.unverified(null).username()).isNull();
    }

    @Test
    void aSubjectOf255CharactersIsAcceptedAndOneMoreIsRefused() {
        assertThat(new ActorIdentity("x", "s".repeat(255)).subject()).hasSize(255);

        assertThatThrownBy(() -> new ActorIdentity("x", "s".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankSubjectIsRefused() {
        assertThatThrownBy(() -> new ActorIdentity("x", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActorIdentity("x", "   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
