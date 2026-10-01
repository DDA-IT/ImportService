package be.dda.catalogimport.domain;

/** Soort bestandsvoorwaarde (changeset 014-5); geen wildcards en geen regex. */
public enum DeliveryFileConditionKind {
    NAME_EQUALS,
    NAME_STARTS_WITH,
    NAME_ENDS_WITH,
    NAME_CONTAINS,
    EXTENSION_IS
}
