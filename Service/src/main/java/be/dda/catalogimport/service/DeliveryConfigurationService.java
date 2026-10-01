package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationFileConditionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationVersionRepository;
import be.dda.catalogimport.domain.AcquisitionKind;
import be.dda.catalogimport.domain.ConnectionProfileVersion;
import be.dda.catalogimport.domain.DeliveryConfiguration;
import be.dda.catalogimport.domain.DeliveryConfigurationFileCondition;
import be.dda.catalogimport.domain.DeliveryConfigurationVersion;
import be.dda.catalogimport.domain.DeliveryFileConditionKind;
import be.dda.catalogimport.domain.DeliveryPostFetchAction;
import be.dda.catalogimport.domain.DeliverySelectionMode;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leveringsconfiguraties (SFTP): bouwstap LC-2 van {@code docs/design/leveringsconfiguratie-design.md} par. 3.2, 6
 * en 10; beslissingslog 2026-09-29 (L3, L7, A3, A12, A17).
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Aanmaken</b> = kop + onveranderlijke versie 1 + bestandsvoorwaarden (L3). Opvolgversies volgen later.</li>
 *   <li><b>Profielversie</b> ({@code connectionProfileVersionId}) moet bestaan (404
 *       {@code CONNECTION_PROFILE_VERSION_NOT_FOUND}).</li>
 *   <li><b>Externe map</b> (A17): absoluut ({@code /...}), segmenten niet leeg en nooit {@code .} of {@code ..}, geen
 *       {@code \}, geen stuurtekens, geen {@code /} achteraan (behalve de wortel {@code /} zelf). Geen recursie.</li>
 *   <li><b>Selectie</b> (design par. 3.2): {@code ALL_FILES} = geen voorwaarden; {@code CONDITIONS} = minstens één
 *       groep met minstens één voorwaarde. Binnen een groep EN, tussen groepen OF. Nummering <b>1-gebaseerd</b>:
 *       {@code group_number} = positie van de groep, {@code sequence_number} = positie binnen de groep (zoals
 *       {@code version_number}).</li>
 *   <li><b>Voorwaarde</b>: soort uit {@link DeliveryFileConditionKind}, letterlijke waarde (geen wildcards of regex:
 *       {@code *} en {@code ?} worden geweigerd, net als {@code /}, {@code \} en stuurtekens), {@code caseSensitive}
 *       verplicht (geen stille default; LC-1 gaf de kolom bewust geen default). {@code EXTENSION_IS} krijgt de
 *       extensie zonder punt vooraan (bv. {@code csv}; betekenis: de naam eindigt op {@code "." + waarde}).</li>
 *   <li><b>Limieten</b> (A12): {@code minFileAgeSeconds} default {@value #DEFAULT_MIN_FILE_AGE_SECONDS}, nooit negatief;
 *       {@code maxFileBytes} default én bovengrens {@link #GLOBAL_MAX_FILE_BYTES} (1 GB, gelijk aan de globale
 *       upload-cap {@code CATALOG_MAX_UPLOAD_SIZE=1GB}); daarboven 400, nooit stil verlaagd.</li>
 *   <li>{@code post_fetch_action} is altijd {@code LEAVE} (A3); de aanvraag kan het niet kiezen.</li>
 *   <li><b>{@code config_hash}</b>: zie {@link #configHash}.</li>
 *   <li>Een aanmaak schrijft geen {@code acquisition_config_event} (geen passende soort in de check-lijst; zie
 *       {@link ConnectionProfileService}).</li>
 * </ul>
 * Foutvolgorde: 400 → 404 → 409; de 403 komt er in de Web-laag vóór.
 */
@Service
public class DeliveryConfigurationService {

    /** Code ontbreekt, is te lang (&gt; 50) of bevat een stuurteken; idem voor de naam. */
    public static final String CODE_CODE_INVALID = "DELIVERY_CONFIGURATION_CODE_INVALID";
    public static final String CODE_NAME_INVALID = "DELIVERY_CONFIGURATION_NAME_INVALID";
    public static final String CODE_PROFILE_VERSION_REQUIRED = "DELIVERY_CONFIGURATION_PROFILE_VERSION_REQUIRED";
    public static final String CODE_REMOTE_DIRECTORY_INVALID = "DELIVERY_CONFIGURATION_REMOTE_DIRECTORY_INVALID";
    public static final String CODE_SELECTION_MODE_INVALID = "DELIVERY_CONFIGURATION_SELECTION_MODE_INVALID";
    public static final String CODE_CONDITIONS_REQUIRED = "DELIVERY_CONFIGURATION_CONDITIONS_REQUIRED";
    public static final String CODE_CONDITIONS_NOT_ALLOWED = "DELIVERY_CONFIGURATION_CONDITIONS_NOT_ALLOWED";
    public static final String CODE_CONDITION_INVALID = "DELIVERY_CONFIGURATION_CONDITION_INVALID";
    public static final String CODE_MIN_FILE_AGE_INVALID = "DELIVERY_CONFIGURATION_MIN_FILE_AGE_INVALID";
    public static final String CODE_MAX_FILE_BYTES_INVALID = "DELIVERY_CONFIGURATION_MAX_FILE_BYTES_INVALID";
    public static final String CODE_REASON_REQUIRED = "DELIVERY_CONFIGURATION_REASON_REQUIRED";
    public static final String CODE_CODE_EXISTS = "DELIVERY_CONFIGURATION_CODE_EXISTS";
    public static final String CODE_NOT_FOUND = "DELIVERY_CONFIGURATION_NOT_FOUND";
    public static final String CODE_PROFILE_VERSION_NOT_FOUND = "CONNECTION_PROFILE_VERSION_NOT_FOUND";

    /** A12: minimale ouderdom van een bestand vóór het opgehaald wordt. */
    public static final int DEFAULT_MIN_FILE_AGE_SECONDS = 300;
    /**
     * A12: globale cap 1 GB, in dezelfde eenheid als Spring {@code DataSize} "1GB" ({@code spring.servlet.multipart.
     * max-file-size}, {@code CATALOG_MAX_UPLOAD_SIZE}): 1024^3 bytes.
     */
    public static final long GLOBAL_MAX_FILE_BYTES = 1024L * 1024L * 1024L;

    /** Versie-/laagaanduiding van de canonieke {@code config_hash}-vorm. */
    static final String HASH_LAYER = "delivery_configuration_version/v1";

    static final int MAX_CODE_LENGTH = 50;
    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_DIRECTORY_LENGTH = 500;
    static final int MAX_VALUE_LENGTH = 200;
    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_ACTOR_LENGTH = 100;
    private static final String UNIQUE_CODE_CONSTRAINT = "uk_delivery_configuration_code";

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryConfigurationService.class);

    /** Invoer: één voorwaarde. */
    public record ConditionInput(String kind, String value, Boolean caseSensitive) {
    }

    /** Invoer: één groep (EN binnen de groep). */
    public record ConditionGroupInput(List<ConditionInput> conditions) {
    }

    /** Invoer van {@link #create}; {@code null} bij de limieten = de default (A12). */
    public record NewDeliveryConfiguration(String code, String name, Long connectionProfileVersionId,
                                           String remoteDirectory, String selectionMode,
                                           List<ConditionGroupInput> conditionGroups, Integer minFileAgeSeconds,
                                           Long maxFileBytes, String reason) {
    }

    /** Eén regel van de lijst: enkel kopgegevens en de laatste versie, geen map. */
    public record DeliveryConfigurationSummary(long id, String code, String name, String acquisitionKind,
                                               boolean active, Long latestVersionId, Integer latestVersionNumber,
                                               Instant createdAt, String createdBy) {
    }

    public record ConditionView(int sequenceNumber, String kind, String value, boolean caseSensitive) {
    }

    public record ConditionGroupView(int groupNumber, List<ConditionView> conditions) {
    }

    /** Eén onveranderlijke versie met haar voorwaarden in vaste volgorde. */
    public record DeliveryConfigurationVersionView(long id, int versionNumber, long connectionProfileVersionId,
                                                   long connectionProfileId, String connectionProfileCode,
                                                   int connectionProfileVersionNumber, String remoteDirectory,
                                                   String selectionMode, int minFileAgeSeconds, long maxFileBytes,
                                                   String postFetchAction, List<ConditionGroupView> conditionGroups,
                                                   Long basedOnVersionId, String changeReason, String configHash,
                                                   Instant createdAt, String createdBy) {
    }

    public record DeliveryConfigurationDetail(long id, String code, String name, String acquisitionKind,
                                              boolean active, Instant createdAt, String createdBy, Instant updatedAt,
                                              List<DeliveryConfigurationVersionView> versions) {
    }

    /** Een gevalideerde voorwaarde met haar 1-gebaseerde nummers. */
    private record ValidCondition(int group, int sequence, DeliveryFileConditionKind kind, String value,
                                  boolean caseSensitive) {
    }

    private final DeliveryConfigurationRepository configurations;
    private final DeliveryConfigurationVersionRepository versions;
    private final DeliveryConfigurationFileConditionRepository conditions;
    private final ConnectionProfileVersionRepository profileVersions;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public DeliveryConfigurationService(DeliveryConfigurationRepository configurations,
                                        DeliveryConfigurationVersionRepository versions,
                                        DeliveryConfigurationFileConditionRepository conditions,
                                        ConnectionProfileVersionRepository profileVersions,
                                        PlatformTransactionManager transactionManager, Clock clock) {
        this.configurations = configurations;
        this.versions = versions;
        this.conditions = conditions;
        this.profileVersions = profileVersions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    // --- Schrijven -------------------------------------------------------------------------------------------

    /**
     * Maakt een Leveringsconfiguratie met versie 1 en haar voorwaarden.
     *
     * @throws BadRequestException 400 invoer ongeldig
     * @throws NotFoundException   404 {@link #CODE_PROFILE_VERSION_NOT_FOUND}
     * @throws ConflictException   409 {@link #CODE_CODE_EXISTS}
     */
    public DeliveryConfigurationDetail create(NewDeliveryConfiguration input, ActorIdentity actor) {
        String by = requireActor(actor);
        if (input == null) {
            throw new BadRequestException(CODE_CODE_INVALID, "Missing request body");
        }
        String code = AcquisitionConfigInput.requireText(input.code(), "code", MAX_CODE_LENGTH, CODE_CODE_INVALID);
        String name = AcquisitionConfigInput.requireText(input.name(), "name", MAX_NAME_LENGTH, CODE_NAME_INVALID);
        if (input.connectionProfileVersionId() == null) {
            throw new BadRequestException(CODE_PROFILE_VERSION_REQUIRED, "Missing connectionProfileVersionId");
        }
        long profileVersionId = input.connectionProfileVersionId();
        String directory = requireRemoteDirectory(input.remoteDirectory());
        DeliverySelectionMode mode = requireSelectionMode(input.selectionMode());
        List<ValidCondition> validConditions = requireConditions(mode, input.conditionGroups());
        int minFileAge = requireMinFileAge(input.minFileAgeSeconds());
        long maxFileBytes = requireMaxFileBytes(input.maxFileBytes());
        String reason = AcquisitionConfigInput.requireText(input.reason(), "reason", MAX_REASON_LENGTH,
                CODE_REASON_REQUIRED);

        return transaction.execute(status -> {
            ConnectionProfileVersion profileVersion = profileVersions.findById(profileVersionId)
                    .orElseThrow(() -> new NotFoundException(CODE_PROFILE_VERSION_NOT_FOUND,
                            "Connection profile version " + profileVersionId + " not found"));
            if (configurations.findByCode(code).isPresent()) {
                throw codeExists(code);
            }
            Instant now = now();
            String hash = configHash(profileVersion.getConnectionProfile().getCode(),
                    profileVersion.getVersionNumber(), directory, mode, minFileAge, maxFileBytes,
                    DeliveryPostFetchAction.LEAVE, validConditions);
            DeliveryConfiguration configuration;
            DeliveryConfigurationVersion version;
            List<DeliveryConfigurationFileCondition> saved = new ArrayList<>();
            try {
                configuration = configurations.saveAndFlush(new DeliveryConfiguration(code, name, AcquisitionKind.SFTP,
                        by, actor.subject(), now));
                version = versions.saveAndFlush(new DeliveryConfigurationVersion(configuration, 1, profileVersion,
                        directory, mode, minFileAge, maxFileBytes, DeliveryPostFetchAction.LEAVE, null, reason, hash,
                        by, actor.subject(), now));
                for (ValidCondition condition : validConditions) {
                    saved.add(conditions.saveAndFlush(new DeliveryConfigurationFileCondition(version,
                            condition.group(), condition.sequence(), condition.kind(), condition.value(),
                            condition.caseSensitive(), null)));
                }
            } catch (DataIntegrityViolationException violation) {
                if (ConnectionProfileService.violates(violation, UNIQUE_CODE_CONSTRAINT)) {
                    throw codeExists(code);
                }
                throw violation;
            }
            LOG.info("Delivery configuration {} (id {}) version 1 created by {} with {} condition(s)", code,
                    configuration.getId(), by, saved.size());
            return detail(configuration, List.of(version), Map.of(version.getId(), saved));
        });
    }

    // --- Lezen -----------------------------------------------------------------------------------------------

    public List<DeliveryConfigurationSummary> list() {
        return readTransaction.execute(status -> {
            Map<Long, DeliveryConfigurationVersion> latest = versions.findLatestVersions().stream()
                    .collect(Collectors.toMap(v -> v.getDeliveryConfiguration().getId(), Function.identity()));
            return configurations.findAllByOrderByCodeAscIdAsc().stream().map(configuration -> {
                DeliveryConfigurationVersion version = latest.get(configuration.getId());
                return new DeliveryConfigurationSummary(configuration.getId(), configuration.getCode(),
                        configuration.getName(), configuration.getAcquisitionKind().name(), configuration.isActive(),
                        version == null ? null : version.getId(), version == null ? null : version.getVersionNumber(),
                        configuration.getCreatedAt(), configuration.getCreatedBy());
            }).toList();
        });
    }

    /** @throws NotFoundException 404 {@link #CODE_NOT_FOUND} */
    public DeliveryConfigurationDetail get(long id) {
        return readTransaction.execute(status -> {
            DeliveryConfiguration configuration = configurations.findById(id)
                    .orElseThrow(() -> new NotFoundException(CODE_NOT_FOUND,
                            "Delivery configuration " + id + " not found"));
            List<DeliveryConfigurationVersion> rows = versions
                    .findByDeliveryConfigurationIdOrderByVersionNumberDesc(configuration.getId());
            Map<Long, List<DeliveryConfigurationFileCondition>> byVersion = new LinkedHashMap<>();
            for (DeliveryConfigurationVersion row : rows) {
                byVersion.put(row.getId(), conditions
                        .findByDeliveryConfigurationVersionIdOrderByGroupNumberAscSequenceNumberAsc(row.getId()));
            }
            return detail(configuration, rows, byVersion);
        });
    }

    // --- config_hash ------------------------------------------------------------------------------------------

    /**
     * De functionele configuratiehash van een DC-versie (hex, 64 tekens). <b>Canonieke vorm (v1):</b>
     * {@code RevisionConfigHashes.hash("delivery_configuration_version/v1", profielcode, profielversienummer,
     * remoteDirectory, selectionMode, minFileAgeSeconds, maxFileBytes, postFetchAction, aantalVoorwaarden, en per
     * voorwaarde in volgorde (groep, volgnummer): groep, volgnummer, soort, caseSensitive, waarde)}. Getallen decimaal,
     * enums als naam, {@code caseSensitive} als {@code true}/{@code false}, teksten zoals opgeslagen (getrimd). De
     * profielversie telt mee via haar stabiele businessreferentie (code + versienummer; beide onveranderlijk), niet via
     * het interne id. Code, naam, reden en actor tellen niet mee. Geen onderdeel kan {@code U+001F} bevatten.
     */
    static String configHash(String profileCode, int profileVersionNumber, String remoteDirectory,
                             DeliverySelectionMode mode, int minFileAgeSeconds, long maxFileBytes,
                             DeliveryPostFetchAction postFetchAction, List<ValidCondition> orderedConditions) {
        List<String> parts = new ArrayList<>();
        parts.add(profileCode);
        parts.add(Integer.toString(profileVersionNumber));
        parts.add(remoteDirectory);
        parts.add(mode.name());
        parts.add(Integer.toString(minFileAgeSeconds));
        parts.add(Long.toString(maxFileBytes));
        parts.add(postFetchAction.name());
        parts.add(Integer.toString(orderedConditions.size()));
        for (ValidCondition condition : orderedConditions) {
            parts.add(Integer.toString(condition.group()));
            parts.add(Integer.toString(condition.sequence()));
            parts.add(condition.kind().name());
            parts.add(Boolean.toString(condition.caseSensitive()));
            parts.add(condition.value());
        }
        return RevisionConfigHashes.hash(HASH_LAYER, parts.toArray(String[]::new));
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    private static DeliveryConfigurationDetail detail(DeliveryConfiguration configuration,
                                                      List<DeliveryConfigurationVersion> rows,
                                                      Map<Long, List<DeliveryConfigurationFileCondition>> byVersion) {
        List<DeliveryConfigurationVersionView> views = rows.stream()
                .map(v -> versionView(v, byVersion.getOrDefault(v.getId(), List.of()))).toList();
        return new DeliveryConfigurationDetail(configuration.getId(), configuration.getCode(), configuration.getName(),
                configuration.getAcquisitionKind().name(), configuration.isActive(), configuration.getCreatedAt(),
                configuration.getCreatedBy(), configuration.getUpdatedAt(), views);
    }

    private static DeliveryConfigurationVersionView versionView(DeliveryConfigurationVersion v,
                                                                List<DeliveryConfigurationFileCondition> rows) {
        Map<Integer, List<ConditionView>> groups = new LinkedHashMap<>();
        rows.stream().sorted((a, b) -> a.getGroupNumber() != b.getGroupNumber()
                        ? Integer.compare(a.getGroupNumber(), b.getGroupNumber())
                        : Integer.compare(a.getSequenceNumber(), b.getSequenceNumber()))
                .forEach(c -> groups.computeIfAbsent(c.getGroupNumber(), g -> new ArrayList<>())
                        .add(new ConditionView(c.getSequenceNumber(), c.getConditionKind().name(),
                                c.getCompareValue(), c.isCaseSensitive())));
        List<ConditionGroupView> groupViews = groups.entrySet().stream()
                .map(e -> new ConditionGroupView(e.getKey(), List.copyOf(e.getValue()))).toList();
        ConnectionProfileVersion profileVersion = v.getConnectionProfileVersion();
        return new DeliveryConfigurationVersionView(v.getId(), v.getVersionNumber(), profileVersion.getId(),
                profileVersion.getConnectionProfile().getId(), profileVersion.getConnectionProfile().getCode(),
                profileVersion.getVersionNumber(), v.getRemoteDirectory(), v.getSelectionMode().name(),
                v.getMinFileAgeSeconds(), v.getMaxFileBytes(), v.getPostFetchAction().name(), groupViews,
                v.getBasedOnVersion() == null ? null : v.getBasedOnVersion().getId(), v.getChangeReason(),
                v.getConfigHash(), v.getCreatedAt(), v.getCreatedBy());
    }

    private static ConflictException codeExists(String code) {
        return new ConflictException(CODE_CODE_EXISTS,
                "A delivery configuration with code '" + code + "' already exists");
    }

    /** A17. De melding noemt de map niet. */
    static String requireRemoteDirectory(String raw) {
        String directory = AcquisitionConfigInput.requireText(raw, "remoteDirectory", MAX_DIRECTORY_LENGTH,
                CODE_REMOTE_DIRECTORY_INVALID);
        if (!directory.startsWith("/")) {
            throw new BadRequestException(CODE_REMOTE_DIRECTORY_INVALID,
                    "remoteDirectory must be absolute (start with /)");
        }
        if (directory.indexOf('\\') >= 0) {
            throw new BadRequestException(CODE_REMOTE_DIRECTORY_INVALID,
                    "remoteDirectory must use / as separator, never \\");
        }
        if (directory.equals("/")) {
            return directory;
        }
        if (directory.endsWith("/")) {
            throw new BadRequestException(CODE_REMOTE_DIRECTORY_INVALID,
                    "remoteDirectory must not end with / (except the root /)");
        }
        for (String segment : directory.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new BadRequestException(CODE_REMOTE_DIRECTORY_INVALID,
                        "remoteDirectory must not contain empty, '.' or '..' segments");
            }
        }
        return directory;
    }

    private static DeliverySelectionMode requireSelectionMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(CODE_SELECTION_MODE_INVALID,
                    "Missing selectionMode; allowed: CONDITIONS, ALL_FILES");
        }
        try {
            return DeliverySelectionMode.valueOf(raw.strip());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(CODE_SELECTION_MODE_INVALID,
                    "Unknown selectionMode; allowed: CONDITIONS, ALL_FILES");
        }
    }

    /** Valideert en nummert (1-gebaseerd) de voorwaarden; de volgorde van de aanvraag blijft behouden. */
    private static List<ValidCondition> requireConditions(DeliverySelectionMode mode, List<ConditionGroupInput> groups) {
        boolean anyGroup = groups != null && !groups.isEmpty();
        if (mode == DeliverySelectionMode.ALL_FILES) {
            if (anyGroup) {
                throw new BadRequestException(CODE_CONDITIONS_NOT_ALLOWED,
                        "selectionMode ALL_FILES takes no conditionGroups");
            }
            return List.of();
        }
        if (!anyGroup) {
            throw new BadRequestException(CODE_CONDITIONS_REQUIRED,
                    "selectionMode CONDITIONS needs at least one condition group with at least one condition");
        }
        List<ValidCondition> result = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            ConditionGroupInput group = groups.get(g);
            if (group == null || group.conditions() == null || group.conditions().isEmpty()) {
                throw new BadRequestException(CODE_CONDITIONS_REQUIRED,
                        "condition group " + (g + 1) + " has no conditions");
            }
            for (int c = 0; c < group.conditions().size(); c++) {
                result.add(requireCondition(group.conditions().get(c), g + 1, c + 1));
            }
        }
        return List.copyOf(result);
    }

    private static ValidCondition requireCondition(ConditionInput input, int group, int sequence) {
        String where = "condition " + group + "." + sequence;
        if (input == null) {
            throw new BadRequestException(CODE_CONDITION_INVALID, where + " is missing");
        }
        DeliveryFileConditionKind kind;
        try {
            kind = DeliveryFileConditionKind.valueOf(input.kind() == null ? "" : input.kind().strip());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(CODE_CONDITION_INVALID, where + " has an unknown kind; allowed: "
                    + List.of(DeliveryFileConditionKind.values()));
        }
        String value = AcquisitionConfigInput.requireText(input.value(), where + " value", MAX_VALUE_LENGTH,
                CODE_CONDITION_INVALID);
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            throw new BadRequestException(CODE_CONDITION_INVALID,
                    where + " value is literal: wildcards (* ?) and regular expressions are not supported");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new BadRequestException(CODE_CONDITION_INVALID,
                    where + " value applies to a file name and must not contain / or \\");
        }
        if (kind == DeliveryFileConditionKind.EXTENSION_IS && (value.startsWith(".") || value.endsWith("."))) {
            throw new BadRequestException(CODE_CONDITION_INVALID,
                    where + ": EXTENSION_IS takes the extension without a leading dot (for example csv)");
        }
        if (input.caseSensitive() == null) {
            throw new BadRequestException(CODE_CONDITION_INVALID, where + " is missing caseSensitive");
        }
        return new ValidCondition(group, sequence, kind, value, input.caseSensitive());
    }

    private static int requireMinFileAge(Integer seconds) {
        if (seconds == null) {
            return DEFAULT_MIN_FILE_AGE_SECONDS;
        }
        if (seconds < 0) {
            throw new BadRequestException(CODE_MIN_FILE_AGE_INVALID, "minFileAgeSeconds must not be negative");
        }
        return seconds;
    }

    private static long requireMaxFileBytes(Long bytes) {
        if (bytes == null) {
            return GLOBAL_MAX_FILE_BYTES;
        }
        if (bytes <= 0 || bytes > GLOBAL_MAX_FILE_BYTES) {
            throw new BadRequestException(CODE_MAX_FILE_BYTES_INVALID,
                    "maxFileBytes must be between 1 and the global cap of " + GLOBAL_MAX_FILE_BYTES + " bytes");
        }
        return bytes;
    }

    private static String requireActor(ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        return ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
