package be.dda.catalogimport.domain;

/**
 * Authenticatiemethode van een {@link ConnectionProfileVersion} ({@code ck_connection_profile_version_auth_method},
 * changeset 014-2). {@link #PASSWORD} hoort bij een {@code SFTP_PASSWORD}-credential, {@link #PRIVATE_KEY} bij een
 * {@code SSH_PRIVATE_KEY}-credential; de databasechecks houden dat in lijn. v1 gebruikt enkel {@code PASSWORD}
 * (A5); {@code PRIVATE_KEY} is gereserveerd.
 */
public enum ConnectionAuthMethod {
    PASSWORD,
    PRIVATE_KEY
}
