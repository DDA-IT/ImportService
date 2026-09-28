/**
 * Contracttypes van de CatalogImport-backend, handgeschreven en 1:1 met de Java-records (geen
 * codegeneratie, zie `docs/design/frontend-scherm3-bundel-design.md` §3.3). Vertaalregels:
 *
 * - `long`/`int`                -> `number`
 * - `Long`/`Integer` (nullable) -> `number | null` ("niet vastgesteld" is niet hetzelfde als 0)
 * - `String` (nullable)         -> `string | null`
 * - `Instant`                   -> `string` (ISO-8601, geen Jackson-configuratie aanwezig)
 * - `BigDecimal`                -> `number | null` (zie de "Ontdekkingen" in §18 van het ontwerp:
 *   geen berekeningen op deze velden in de UI)
 * - Java-enum                   -> string-union + `as const`-array, zodat de UI onbekende
 *   toekomstige waarden nog steeds kan tonen in plaats van te crashen.
 */

// be.dda.catalogimport.service.PageResult
export type PageResult<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

// be.dda.catalogimport.domain.PublicationBundleStatus
export const PUBLICATION_BUNDLE_STATUSES = [
  'ASSEMBLING',
  'FROZEN',
  'CANCELLED',
  'PUBLISHING',
  'PARTIALLY_PUBLISHED',
  'PUBLISHED',
  'PUBLICATION_FAILED',
] as const;
export type PublicationBundleStatus = (typeof PUBLICATION_BUNDLE_STATUSES)[number];

// be.dda.catalogimport.domain.PublicationTargetMode
export const PUBLICATION_TARGET_MODES = ['SIMULATION', 'TRIAL_LIBRARY', 'PRODUCTION'] as const;
export type PublicationTargetMode = (typeof PUBLICATION_TARGET_MODES)[number];

// be.dda.catalogimport.web Permission (5-PERM): de rechtcodes zoals `GET /me.permissions` ze levert.
export const PERMISSION_READ = 'catalogImport.read';
export const PERMISSION_MANAGE = 'catalogImport.manage';
export const PERMISSION_APPROVE = 'catalogImport.approve';
export const PERMISSIONS = [PERMISSION_READ, PERMISSION_MANAGE, PERMISSION_APPROVE] as const;
export type Permission = (typeof PERMISSIONS)[number];

// be.dda.catalogimport.domain.MutationStatus
export const MUTATION_STATUSES = [
  'PLANNED',
  'BLOCKED',
  'AWAITING_APPROVAL',
  'READY_FOR_PUBLICATION',
  'IN_PROGRESS',
  'PUBLISHED',
  'TECHNICALLY_FAILED',
  'REJECTED',
  'EXPIRED',
  'SKIPPED',
  'RECORDED',
] as const;
export type MutationStatus = (typeof MUTATION_STATUSES)[number];

// be.dda.catalogimport.domain.MutationActionType
export const MUTATION_ACTION_TYPES = [
  'CREATE',
  'UPDATE',
  'IDENTITY_REFERENCE_INCIDENT',
  'IMPORT_MARKER',
] as const;
export type MutationActionType = (typeof MUTATION_ACTION_TYPES)[number];

// be.dda.catalogimport.domain.MutationTargetDomain
export const MUTATION_TARGET_DOMAINS = ['OFFER', 'IMPORT'] as const;
export type MutationTargetDomain = (typeof MUTATION_TARGET_DOMAINS)[number];

// be.dda.catalogimport.domain.BundleDecisionKind
export const BUNDLE_DECISION_KINDS = ['APPROVE', 'REJECT', 'AUTO_APPROVE_PLANNED', 'FREEZE', 'CANCEL'] as const;
export type BundleDecisionKind = (typeof BUNDLE_DECISION_KINDS)[number];

// be.dda.catalogimport.domain.BundleDecisionScope
export const BUNDLE_DECISION_SCOPES = ['MUTATION', 'GROUP', 'BUNDLE'] as const;
export type BundleDecisionScope = (typeof BUNDLE_DECISION_SCOPES)[number];

// be.dda.catalogimport.domain.ImportBatchStatus
export const IMPORT_BATCH_STATUSES = [
  'RECEIVED',
  'SCREENING',
  'MUTATING',
  'SCREENED',
  'BLOCKED',
  'FAILED',
  'BASELINE_ACCEPTED',
] as const;
export type ImportBatchStatus = (typeof IMPORT_BATCH_STATUSES)[number];

// be.dda.catalogimport.domain.ValidationResult
export const VALIDATION_RESULTS = ['VALID', 'VALID_WITH_WARNINGS', 'REVIEW_REQUIRED', 'BLOCKING'] as const;
export type ValidationResult = (typeof VALIDATION_RESULTS)[number];

// be.dda.catalogimport.domain.CreationOutcome (het oordeel van het creatiebeleid; `null` zolang pass
// E4b niet gedraaid heeft — nooit stil `AUTOMATIC`)
export const CREATION_OUTCOMES = ['AUTOMATIC', 'INITIAL_LOAD', 'THRESHOLD_EXCEEDED'] as const;
export type CreationOutcome = (typeof CREATION_OUTCOMES)[number];

// be.dda.catalogimport.domain.TaskTriggerType
export const TASK_TRIGGER_TYPES = ['MANUAL', 'SCHEDULED'] as const;
export type TaskTriggerType = (typeof TASK_TRIGGER_TYPES)[number];

// be.dda.catalogimport.service.BundleQueryService.BundleSummary
export type BundleSummary = {
  id: number;
  bundleReference: string;
  description: string | null;
  status: PublicationBundleStatus;
  targetMode: PublicationTargetMode;
  targetMoment: string | null;
  publicationPolicy: string | null;
  createdBy: string;
  createdAt: string;
  frozenBy: string | null;
  frozenAt: string | null;
  frozenReason: string | null;
  cancelledBy: string | null;
  cancelledAt: string | null;
  cancelledReason: string | null;
  batchCount: number | null;
  contentMutationCount: number | null;
  readyCount: number | null;
  rejectedCount: number | null;
  blockedCount: number | null;
  expiredCount: number | null;
  identityIncidentCount: number | null;
  bulkIncidentCount: number | null;
  criticalIssueCount: number | null;
  warningCount: number | null;
};

// be.dda.catalogimport.service.BundleQueryService.BundleDetail
export type BundleDetail = BundleSummary & {
  /** Niet-blokkerende baselinecontrole; `null` zodra de bundel niet meer ASSEMBLING is. */
  staleMutationCount: number | null;
  /** Hexadecimale bundelhash; alleen gevuld zodra de bundel (ooit) FROZEN is geweest. */
  contentHash: string | null;
  /**
   * Bouwstap C2: het aantal `PLANNED`-mutaties dat het bevriezen in bulk goedkeurt op naam van de
   * bevriezer (`PublicationBundleDao.countPlanned`). Alleen live gevuld bij ASSEMBLING; `null` bij
   * FROZEN/CANCELLED — dan is het geen levend getal meer en wordt het als "—" getoond, nooit als 0.
   */
  plannedCount: number | null;
  /**
   * Bouwstap C2: het aantal mutaties dat nog op een beslissing wacht (`countUndecided`) — de
   * blokkadevoorwaarde van het bevriezen (`BUNDLE_HAS_UNDECIDED_MUTATIONS`). Alleen live gevuld bij
   * ASSEMBLING; `null` bij FROZEN/CANCELLED.
   */
  awaitingApprovalCount: number | null;
  /**
   * Bouwstap C7: het aantal mutaties dat bij annuleren `EXPIRED` wordt (`countExpirableMutations`, dezelfde
   * selectie als het annuleren zelf). Gevuld bij ASSEMBLING én FROZEN; `null` bij CANCELLED. Een momentopname.
   */
  expirableCount: number | null;
};

// be.dda.catalogimport.service.BundleQueryService.BundleBatchRow
export type BundleBatchRow = {
  id: number;
  bundleId: number;
  batchId: number;
  importLinkId: number;
  addedBy: string;
  addedAt: string;
  removedBy: string | null;
  removedAt: string | null;
  removedReason: string | null;
  active: boolean;
  batchStatus: ImportBatchStatus;
  batchContentMutationCount: number | null;
};

// be.dda.catalogimport.service.PublicationBundleService.BundleCandidate
export type BundleCandidate = {
  batchId: number;
  importLinkId: number;
  status: ImportBatchStatus;
  validationResult: ValidationResult | null;
  contentMutationCount: number | null;
  finishedAt: string | null;
};

// be.dda.catalogimport.service.BatchQueryService.MutationRow (ook gebruikt door
// GET /bundles/{id}/mutations, bewust identiek — zie het herbruikbare mutatielijst-component §11)
export type MutationRow = {
  id: number;
  batchId: number;
  actionType: MutationActionType;
  targetDomain: MutationTargetDomain;
  status: MutationStatus;
  statusReason: string | null;
  identitySupplier: string | null;
  identitySupplierGroup: string | null;
  identitySupplierReference: string | null;
  identityDiscountCode: string | null;
  identityDiscountState: string | null;
  domainMask: string | null;
  beforeBasePrice: number | null;
  afterBasePrice: number | null;
  basePriceCurrency: string | null;
  referenceType: string | null;
  beforeReferenceValue: string | null;
  afterReferenceValue: string | null;
  sourceStateId: number | null;
  sourceRowNumber: number | null;
  resultSummary: string | null;
  idempotencyKey: string | null;
  createdAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decidedFromStatus: string | null;
  decisionId: number | null;
  /**
   * Bouwstap C4: de identiteitshash als hexadecimale tekst in kleine letters — de sleutel van de
   * wijzigingsgroep `(batchId, identityHash)`. `null` wanneer de kolom leeg is, wat per definitie zo
   * is voor de `IMPORT_MARKER`. De UI interpreteert deze waarde nooit; ze toont hem en kan er
   * serverzijdig op filteren (`?identityHash=`), zie §11.5 en `docs/decisions.md` 2026-09-24.
   */
  identityHash: string | null;
};

// be.dda.catalogimport.service.BundleQueryService.DecisionRow
export type DecisionRow = {
  id: number;
  bundleId: number;
  mutationId: number | null;
  decisionKind: BundleDecisionKind;
  decisionScope: BundleDecisionScope;
  selectionFilter: string | null;
  previousStatus: string;
  newStatus: string;
  affectedCount: number;
  decidedBy: string;
  decidedAt: string;
  reason: string | null;
};

// be.dda.catalogimport.service.BundleDecisionService.MutationDecisionView
export type MutationDecisionView = {
  mutation: MutationRow;
  decision: DecisionRow;
  idempotent: boolean;
};

// be.dda.catalogimport.service.BundleDecisionService.GroupDecisionView
export type GroupDecisionView = {
  decisionId: number | null;
  affectedCount: number;
  selectionFilter: string;
};

/**
 * be.dda.catalogimport.service.BundleFreezeService.FreezePreflight — de droogloop van het bevriezen
 * (`GET /bundles/{id}/freeze-check`, bouwstap C3). Een MOMENTOPNAME ZONDER SLOT: `freezable: true` is
 * nooit een garantie, `POST /bundles/{id}/freeze` controleert alles opnieuw en blijft de waarheid.
 * `blockerCodes` zijn de stabiele foutcodes die `freeze` zou geven; de conflictlijsten bevatten
 * hoogstens tien leesbare voorbeelden. Wordt door F10 (`FreezeDialog`) gebruikt.
 */
export type FreezePreflight = {
  freezable: boolean;
  blockerCodes: string[];
  batchCount: number;
  plannedCount: number;
  awaitingApprovalCount: number;
  staleMutationCount: number;
  inBundleConflicts: string[];
  crossBundleConflicts: string[];
};

/**
 * be.dda.catalogimport.service.BundleDecisionService.DecisionFilter (request). Sinds bouwstap C5
 * dezelfde vijf velden als de queryparameters van `GET /bundles/{id}/mutations`, zodat de groepsactie
 * exact beslist over wat de gefilterde lijst toont (`docs/decisions.md` 2026-09-24). `identityHash` is
 * hexadecimaal en hoofdletterongevoelig; een ongeldige of onbekende hash raakt 0 mutaties, geen fout.
 */
export type DecisionFilter = {
  batchId?: number;
  status?: MutationStatus;
  statusReason?: string;
  actionType?: MutationActionType;
  identityHash?: string;
};

// be.dda.catalogimport.service.PublicationBundleService.BundleReference
export type BundleReference = {
  id: number;
  bundleReference: string;
  description: string | null;
  status: PublicationBundleStatus;
  targetMode: PublicationTargetMode;
  targetMoment: string | null;
  publicationPolicy: string | null;
  createdBy: string;
  createdAt: string;
  idempotencyKey: string | null;
  /** `false` bij een idempotente hervinding van een bestaande bundel (zelfde referentie/scope). */
  created: boolean;
};

// be.dda.catalogimport.service.PublicationBundleService.Membership
export type Membership = {
  id: number;
  bundleId: number;
  batchId: number;
  importLinkId: number;
  addedBy: string;
  addedAt: string;
  removedBy: string | null;
  removedAt: string | null;
  removedReason: string | null;
  active: boolean;
};

// be.dda.catalogimport.service.SourceStateBaselineService.BaselineAcceptance
export type BaselineAcceptance = {
  batchId: number;
  status: ImportBatchStatus;
  acceptedBy: string;
  acceptedAt: string;
  reason: string;
  newCount: number | null;
  changedCount: number | null;
  unchangedCount: number | null;
  skippedMutationCount: number;
};

// be.dda.catalogimport.web.CatalogImportBundleController.CreateBundleRequest (request)
export type CreateBundleRequest = {
  bundleReference: string;
  description: string | null;
  targetMode: PublicationTargetMode;
  targetMoment: string | null;
  publicationPolicy: string | null;
  createdBy: string;
};

// be.dda.catalogimport.web.CatalogImportBundleController.AddBatchesRequest (request)
export type AddBatchesRequest = {
  batchIds: number[];
  addedBy: string;
};

// be.dda.catalogimport.web.CatalogImportBundleController.RemoveBatchRequest (request)
export type RemoveBatchRequest = {
  removedBy: string;
  reason: string;
};

// be.dda.catalogimport.web.CatalogImportBundleController.DecideMutationRequest (request)
export type DecideMutationRequest = {
  decidedBy: string;
  reason: string | null;
};

// be.dda.catalogimport.web.CatalogImportBundleController.DecideGroupRequest (request)
export type DecideGroupRequest = {
  decisionKind: BundleDecisionKind;
  decidedBy: string;
  reason: string | null;
  filter: DecisionFilter;
};

// be.dda.catalogimport.web.CatalogImportBundleController.FreezeBundleRequest (request)
export type FreezeBundleRequest = {
  frozenBy: string;
  reason: string;
};

// be.dda.catalogimport.web.CatalogImportBundleController.CancelBundleRequest (request)
export type CancelBundleRequest = {
  cancelledBy: string;
  reason: string;
};

// be.dda.catalogimport.web.CatalogImportBatchController.AcceptBaselineRequest (request)
export type AcceptBaselineRequest = {
  acceptedBy: string;
  reason: string;
};

/**
 * be.dda.catalogimport.service.BatchQueryService.BatchDetail — de volledige stand van één batch
 * (`GET /batches/{id}`), gebruikt door het batchdetailscherm (A-F1). `importLinkCode`/`supplierCode`/
 * `libraryCode` zijn additief toegevoegd in bouwstap A-B1 zodat de UI niet "koppeling #7" hoeft te
 * tonen (§16.5 van het scherm-3-ontwerp). Elke `number | null`-teller betekent "niet vastgesteld" en
 * wordt als "—" getoond, nooit als 0.
 */
export type BatchDetail = {
  batchId: number;
  deliveryId: number;
  importLinkId: number;
  /** A-B1: de code van de koppeling, bv. `LNK-1`. */
  importLinkCode: string;
  /** A-B1: de code van de leverancierorganisatie van die koppeling. */
  supplierCode: string;
  /** A-B1: de doelbibliotheek van die koppeling, bv. `PSARF012`. */
  libraryCode: string;
  definitionRevisionId: number;
  taskRunId: number | null;
  attemptNo: number;
  status: ImportBatchStatus;
  validationResult: ValidationResult | null;
  startedAt: string | null;
  finishedAt: string | null;
  stagedRowCount: number;
  mutationProgressRowNumber: number;
  rawRecordCount: number | null;
  validRecordCount: number | null;
  rejectedRecordCount: number | null;
  filteredOutCount: number | null;
  errorBeforeFilterCount: number | null;
  duplicateIdentityCount: number | null;
  newCount: number | null;
  changedCount: number | null;
  unchangedCount: number | null;
  identityIncidentCount: number | null;
  contentMutationCount: number | null;
  bulkIncidentCount: number | null;
  criticalLineCount: number | null;
  criticalIssueCount: number | null;
  warningCount: number | null;
  awaitingApprovalCount: number | null;
  creationOutcome: CreationOutcome | null;
  creationScopeCount: number | null;
  creationCandidateCount: number | null;
  blockedCode: string | null;
  blockedReason: string | null;
  baselineAcceptedBy: string | null;
  baselineAcceptedAt: string | null;
  baselineAcceptReason: string | null;
  createdAt: string;
  createdBy: string | null;
};

// be.dda.catalogimport.service.BatchQueryService.BatchRow (Scherm 0, D14, bouwstap S0-B1)
export type BatchRow = {
  batchId: number;
  deliveryId: number;
  importLinkId: number;
  importLinkCode: string;
  supplierCode: string;
  libraryCode: string;
  attemptNo: number;
  status: ImportBatchStatus;
  validationResult: ValidationResult | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  rawRecordCount: number | null;
  validRecordCount: number | null;
  rejectedRecordCount: number | null;
  contentMutationCount: number | null;
  awaitingApprovalCount: number | null;
  criticalLineCount: number | null;
  criticalIssueCount: number | null;
  warningCount: number | null;
  bulkIncidentCount: number | null;
  identityIncidentCount: number | null;
  blockedCode: string | null;
  baselineAcceptedBy: string | null;
  baselineAcceptedAt: string | null;
};

/**
 * be.dda.catalogimport.service.BatchQueryService.IssueRow (A-F2). `rowNumber` is `null` voor een
 * leverings-/structuurprobleem; de rijen zijn voorbeelden, geen volledige telling.
 */
export type IssueRow = {
  id: number;
  rowNumber: number | null;
  issueCode: string;
  fieldName: string | null;
  severity: string;
  issueDomain: string;
  controlLevel: string;
  impactScope: string;
  handlingStatus: string;
  sourceValue: string | null;
  expectedValue: string | null;
  message: string | null;
  issueGroupId: number | null;
  createdAt: string;
};

/**
 * be.dda.catalogimport.service.BatchQueryService.IssueGroupRow (A-F2). `occurrenceCount` is het
 * werkelijke aantal; `recordedSampleCount` het aantal bewaarde voorbeeldrijen. `scopeRecordCount` en
 * `sharePercent` zijn `null` als de scope onbekend is (nooit een geraden noemer).
 */
export type IssueGroupRow = {
  id: number;
  issueCode: string;
  signature: string;
  severity: string;
  issueDomain: string;
  controlLevel: string;
  impactScope: string;
  incidentKind: string;
  occurrenceCount: number;
  recordedSampleCount: number;
  scopeRecordCount: number | null;
  sharePercent: number | null;
  bulkIncident: boolean;
  priceComponentCode: string | null;
  deviationDirection: string | null;
  dominantFactor: number | null;
  referenceType: string | null;
  patternDescription: string | null;
  firstRowNumber: number | null;
  firstDetectedAt: string;
  lastDetectedAt: string;
  handlingStatus: string;
};

// be.dda.catalogimport.service.DeliveryView.FileView
export type DeliveryFileView = {
  sequenceNumber: number;
  fileName: string;
  contentHash: string;
  hashAlgorithm: string;
  byteSize: number;
};

/** be.dda.catalogimport.service.DeliveryView.BatchView — status en tellers van de laatste batch. */
export type DeliveryBatchView = {
  batchId: number;
  status: string;
  validationResult: string | null;
  attemptNo: number;
  rawRecordCount: number | null;
  validRecordCount: number | null;
  rejectedRecordCount: number | null;
  filteredOutCount: number | null;
  errorBeforeFilterCount: number | null;
  duplicateIdentityCount: number | null;
  newCount: number | null;
  changedCount: number | null;
  unchangedCount: number | null;
  contentMutationCount: number | null;
  criticalLineCount: number | null;
  criticalIssueCount: number | null;
  warningCount: number | null;
  awaitingApprovalCount: number | null;
  creationOutcome: string | null;
  creationScopeCount: number | null;
  creationCandidateCount: number | null;
  blockedCode: string | null;
  blockedReason: string | null;
};

/** be.dda.catalogimport.service.DeliveryView (`GET /deliveries/{id}`). Het archiefpad wordt niet blootgesteld. */
export type DeliveryView = {
  deliveryId: number;
  taskId: number;
  taskRunId: number | null;
  idempotencyKey: string;
  receivedAt: string;
  expectedFileCount: number | null;
  actualFileCount: number;
  expectedRecordCount: number | null;
  actualRecordCount: number | null;
  expectedByteSize: number | null;
  actualByteSize: number;
  completenessProven: boolean;
  manifestReference: string | null;
  files: DeliveryFileView[];
  batch: DeliveryBatchView | null;
};

// be.dda.catalogimport.service.BatchQueryService.StatusCount
export type StatusCount = { status: ImportBatchStatus; count: number };

// be.dda.catalogimport.service.BatchQueryService.ValidationCount ("niet vastgesteld" =
// validationResult === null, altijd een eigen zichtbare regel, nooit als 0 getoond of samengevoegd
// met VALID)
export type ValidationCount = { validationResult: ValidationResult | null; count: number };

// be.dda.catalogimport.service.BatchQueryService.BatchSummary (Scherm 0, D14, bouwstap S0-B2)
export type BatchSummary = {
  total: number;
  byStatus: StatusCount[];
  byValidationResult: ValidationCount[];
};

// be.dda.catalogimport.service.ImportLinkQueryService.ImportLinkRow (Scherm 0/3, D14, bouwstap S0-B3)
// `importDefinitionId` is additief (S1-B1): sluit de boom van scherm 1a tot op koppelingenniveau.
export type ImportLinkRow = {
  id: number;
  code: string;
  name: string;
  supplierCode: string;
  supplierName: string;
  libraryCode: string;
  active: boolean;
  importDefinitionId: number;
};

// be.dda.catalogimport.domain.SourceOrganisationType (S1-B1)
export const SOURCE_ORGANISATION_TYPES = ['SUPPLIER', 'PURCHASING_ASSOCIATION'] as const;
export type SourceOrganisationType = (typeof SOURCE_ORGANISATION_TYPES)[number];

// be.dda.catalogimport.domain.DefinitionUsageType (S1-B1)
export const DEFINITION_USAGE_TYPES = ['OWN_DEFINITION', 'REUSABLE_TEMPLATE'] as const;
export type DefinitionUsageType = (typeof DEFINITION_USAGE_TYPES)[number];

// be.dda.catalogimport.domain.RevisionStatus (S1-B1)
export const REVISION_STATUSES = [
  'DRAFT',
  'SCREENING',
  'REVIEW_REQUIRED',
  'PENDING_APPROVAL',
  'ACTIVE',
  'SUPERSEDED',
  'WITHDRAWN',
] as const;
export type RevisionStatus = (typeof REVISION_STATUSES)[number];

// be.dda.catalogimport.service.SetupQueryService.SourceOrganisationRow (S1-B1, scherm 1a)
export type SourceOrganisationRow = {
  id: number;
  code: string;
  name: string;
  type: SourceOrganisationType;
  active: boolean;
};

/**
 * be.dda.catalogimport.service.SetupQueryService.DefinitionRow (S1-B1, scherm 1a).
 * `activeRevisionId` is `null` als er geen ACTIVE-revisie is — nooit hetzelfde als "revisie #0".
 */
export type DefinitionRow = {
  id: number;
  code: string;
  name: string;
  usageType: DefinitionUsageType;
  sourceOrganisationId: number;
  sourceOrganisationCode: string;
  activeRevisionId: number | null;
};

// be.dda.catalogimport.service.SetupQueryService.RevisionRow (S1-B1, scherm 1a)
export type RevisionRow = {
  id: number;
  definitionId: number;
  revisionNumber: number;
  status: RevisionStatus;
};

/**
 * be.dda.catalogimport.service.TaskQueryService.TaskRow — alleen-lezen takenlijst (`GET /tasks`,
 * bouwstap B-B1): laat de UI kiezen op welke taak een levering geüpload wordt. `triggerType` staat
 * erbij omdat een niet-`MANUAL`-taak door de intake geweigerd wordt (`TASK_NOT_MANUAL`); zo'n taak
 * wordt uitgeschakeld mét reden getoond. `lastRunStartedAt`/`lastRunFinishedAt` zijn `null` zonder
 * run. Wordt door B-F1 (uploadscherm) gebruikt.
 */
export type TaskRow = {
  id: number;
  name: string;
  active: boolean;
  triggerType: TaskTriggerType;
  preventConcurrentRuns: boolean;
  importLinkId: number;
  importLinkCode: string;
  supplierCode: string;
  libraryCode: string;
  lastRunStartedAt: string | null;
  lastRunFinishedAt: string | null;
};

// be.dda.catalogimport.domain.PublicationRunStatus (5-PUB-a zet enkel REQUESTED/PREPARING/SIMULATED/
// FAILED; de overige zeven zijn gedeclareerd voor 5-PUB-b/c en worden nooit gezet, maar de UI moet ze
// verdragen — zie StatusBadge-conventie "onbekend wordt getoond, niet weggelaten").
export const PUBLICATION_RUN_STATUSES = [
  'REQUESTED',
  'PREPARING',
  'SIMULATED',
  'FAILED',
  'WAITING_FOR_TARGET_CONTRACT',
  'READY_FOR_DELIVERY',
  'WRITTEN_TO_PSIMPORT',
  'RESULT_UNKNOWN',
  'APPLIED',
  'REJECTED_BY_PRODIS',
  'RECOVERY_REQUIRED',
] as const;
export type PublicationRunStatus = (typeof PUBLICATION_RUN_STATUSES)[number];

/**
 * be.dda.catalogimport.service.PublicationRunService.PublicationRunView (5-PUB-a, bouwstap 5P-8).
 * `startedAt` wordt in dezelfde transactie als de aanmaak gezet (`markPreparing`) en is dus in de
 * praktijk altijd gevuld; `finishedAt` blijft `null` zolang de run niet terminaal is (`SIMULATED`/
 * `FAILED`) — inclusief het edge-case risico van een vastgelopen `PREPARING`-run zonder herstel
 * (`docs/design/fase5-pub-design.md` §7). `simulationOnly`/`writesToProdis`/`contractStatus` staan
 * altijd vast: geen contractuele toezegging, enkel een waarschuwing dat er niets uitgevoerd is.
 */
export type PublicationRunView = {
  id: number;
  bundleId: number;
  targetMode: PublicationTargetMode;
  attempt: number;
  status: PublicationRunStatus;
  requestedBy: string;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  bundleContentHash: string | null;
  snapshotHash: string | null;
  payloadHash: string | null;
  artifactSha256: string | null;
  artifactByteSize: number | null;
  rowCount: number | null;
  incompleteRowCount: number | null;
  failureCode: string | null;
  failureMessage: string | null;
  simulationOnly: boolean;
  writesToProdis: boolean;
  contractStatus: string;
  previewSpecVersion: string;
  snapshotSpecVersion: string | null;
};

// be.dda.catalogimport.web.PublicationRunController.RequestRunRequest (request). Geen actorveld: de
// aanvrager komt uit de login (CurrentActor).
export type RequestRunRequest = {
  targetMode: PublicationTargetMode;
};

/**
 * be.dda.catalogimport.web.CatalogImportDeliveryController.UploadResponse (`POST /tasks/{id}/deliveries`).
 * Tellers zijn `null` wanneer de screening er niet aan toegekomen is — nooit als 0 lezen. `status` is
 * `SCREENED`, `BLOCKED` (met `blockedCode`) of `FAILED` (ook dan HTTP 201).
 */
export type UploadResponse = {
  deliveryId: number;
  batchId: number;
  deliveryReference: string;
  status: string;
  blockedCode: string | null;
  rawRecordCount: number | null;
  validRecordCount: number | null;
  rejectedRecordCount: number | null;
  duplicateIdentityCount: number | null;
  newCount: number | null;
  changedCount: number | null;
  unchangedCount: number | null;
  contentMutationCount: number | null;
};

/**
 * be.dda.catalogimport.web.CatalogImportDeliveryController — `GET /local-source/files`-rij (tweede
 * ontvangstweg, `docs/decisions.md` 2026-09-27). Geen pad: enkel de kale bestandsnaam.
 */
export type LocalSourceFile = {
  fileName: string;
  byteSize: number;
  lastModifiedAt: string;
};

/** `GET /local-source/files` (MANAGE), gesorteerd meest-recent-eerst, cap 500 + `truncated`. */
export type LocalSourceListing = {
  files: LocalSourceFile[];
  truncated: boolean;
};

/**
 * be.dda.catalogimport.service.TemplateBookmarkService.TemplateView (S1-F2, sjabloon-/
 * materialisatiewizard, scherm 1b alleen-lezen deel). `GET /templates`, achter
 * `catalogimport.setup-api.enabled` (ongewijzigd, blijft dicht in productie — zie `docs/decisions.md`
 * 2026-09-27 "scherm 1a/1b").
 */
export type TemplateView = {
  id: number;
  code: string;
  name: string;
  sourceOrganisationId: number;
  sourceOrganisationCode: string;
};

// be.dda.catalogimport.service.TemplateBookmarkService.UsageView (S1-F2)
export type BookmarkUsageView = {
  id: number;
  placeKind: string;
  targetHint: string;
};

// be.dda.catalogimport.service.TemplateBookmarkService.BookmarkView (S1-F2)
export type BookmarkView = {
  id: number;
  revisionId: number;
  name: string;
  label: string;
  description: string | null;
  dataType: string;
  valueScope: string;
  ownerRole: string;
  required: boolean;
  defaultValue: string | null;
  allowedValues: string | null;
  validationPattern: string | null;
  sortOrder: number;
  usages: BookmarkUsageView[];
};

// be.dda.catalogimport.service.TemplateBookmarkService.ProblemView (S1-F2) — niet-blokkerende fase
// C-bevindingen, getoond als `<ul role="alert">`, patroon van BundleOverviewTab's `staleWarning`.
export type ProblemView = {
  code: string;
  bookmarkName: string;
  message: string;
};

/**
 * be.dda.catalogimport.service.TemplateBookmarkService.BookmarkSetView (S1-F2) — de invulset van één
 * sjabloonrevisie (`GET /templates/{definitionId}/revisions/{revisionId}/bookmarks`), inclusief de
 * niet-blokkerende `problems`-lijst.
 */
export type BookmarkSetView = {
  definitionId: number;
  revisionId: number;
  bookmarks: BookmarkView[];
  problems: ProblemView[];
};

/**
 * be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisedDefinitionView (S1-F2) —
 * één rij van `GET /templates/{definitionId}/materialisations`: een definitie die al uit dit sjabloon
 * voortkwam. `definitionRevisionId`/`definitionRevisionNumber`/`definitionRevisionStatus` zijn `null`
 * wanneer geen enkele revisie van deze definitie een herkomstrevisie uit dit sjabloon draagt.
 */
export type MaterialisedDefinitionView = {
  definitionId: number;
  definitionCode: string;
  definitionName: string;
  definitionRevisionId: number | null;
  definitionRevisionNumber: number | null;
  definitionRevisionStatus: string | null;
  templateRevisionId: number | null;
  templateRevisionNumber: number | null;
  templateRevisionStatus: string | null;
  importLinkCount: number;
  shareable: boolean;
  blockingBookmarkName: string | null;
};

// --- S1-F3: het schrijfdeel van scherm 1b (materialiseren + bookmarkwaarde wijzigen) ---------------
// `docs/decisions.md` 2026-09-27 "scherm 1a/1b", S1-F3-alinea. Zelfde vlag als het leesdeel.

/**
 * be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode — `NEW_DEFINITION`
 * maakt definitie + revisie + koppeling, `REUSE_DEFINITION` hangt alleen een koppeling aan een
 * bestaande, deelbare definitie. **Bewust zonder default** (ontwerp §4 B1): een stil geraden keuze
 * bepaalt of twee leveranciers voortaan één configuratie delen. De UI mag die default dus ook niet
 * via een voorselectie terug invoeren.
 */
export const MATERIALISATION_MODES = ['NEW_DEFINITION', 'REUSE_DEFINITION'] as const;
export type MaterialisationMode = (typeof MATERIALISATION_MODES)[number];

/**
 * be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue — `value: ''` is een
 * uitdrukkelijk lege waarde en wordt zo bewaard; een ontbrekend veld wordt geweigerd. Die twee zijn
 * nooit hetzelfde (R-BMK-03), dus `value` is hier nooit `null`.
 */
export type MaterialiseBookmarkValue = { name: string; value: string };

/**
 * be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest — body van
 * `POST /templates/{definitionId}/materialisations`.
 *
 * Velden die bij de gekozen modus niet horen, worden `null` gelaten (niet weggelaten met een andere
 * betekenis): `reuseDefinitionId` alleen bij `REUSE_DEFINITION` (anders 400
 * `REUSE_DEFINITION_NOT_ALLOWED`), `definitionCode`/`definitionName`/`changeReason` alleen bij
 * `NEW_DEFINITION` (bij hergebruik landen ze nergens en weigert de backend ze). Vult een bookmark een
 * `LINK_*`-plaats, dan blijft het bijhorende requestveld `null` — één bron per waarde, anders 400
 * `LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT` (ontwerp §4 D7).
 */
export type MaterialiseRequest = {
  templateRevisionId: number | null;
  mode: MaterialisationMode;
  reuseDefinitionId: number | null;
  definitionCode: string | null;
  definitionName: string | null;
  changeReason: string | null;
  linkCode: string;
  linkName: string;
  supplierOrganisationCode: string | null;
  libraryCode: string | null;
  librarySearchSupplierCode: string | null;
  bookmarkValues: MaterialiseBookmarkValue[];
  materialisedBy: string | null;
};

// be.dda.catalogimport.service.TemplateMaterialisationService.AppliedValue (S1-F3)
export type AppliedValue = {
  name: string;
  dataType: string;
  value: string;
  placeKind: string;
  targetHint: string;
};

/**
 * be.dda.catalogimport.service.TemplateMaterialisationService.Warning (S1-F3) — getypeerd, geen vrije
 * tekst: `OPTIONAL_BOOKMARK_NOT_FILLED` of `LINK_SEARCH_SUPPLIER_NOT_DERIVED` (dan is `bookmarkName`
 * `null` wanneer geen enkele bookmark die plaats declareerde).
 */
export type MaterialisationWarning = {
  code: string;
  bookmarkName: string | null;
  message: string;
};

/**
 * be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView (S1-F3) — het
 * antwoord van de materialisatie. `templateRevisionNumber`/`templateRevisionStatus` tonen altijd welke
 * sjabloonversie effectief gebruikt is (ook een `SUPERSEDED`: dat mag, maar nooit stilzwijgend);
 * `definitionCreated` onderscheidt nieuw van hergebruikt.
 */
export type MaterialisationView = {
  templateDefinitionId: number;
  templateRevisionId: number;
  templateRevisionNumber: number;
  templateRevisionStatus: string;
  definitionId: number;
  definitionCode: string;
  definitionCreated: boolean;
  definitionRevisionId: number;
  definitionRevisionNumber: number;
  definitionRevisionStatus: string;
  importLinkId: number;
  importLinkCode: string;
  definitionValues: AppliedValue[];
  linkValues: AppliedValue[];
  warnings: MaterialisationWarning[];
};

/**
 * be.dda.catalogimport.service.LinkBookmarkValueService.LinkBookmarkValueRow (S1-F3) — één ingevulde
 * LINK-bookmarkwaarde van een koppeling. `declared = false` is een wees: de naam staat niet (meer)
 * gedeclareerd op de actieve revisie, de waarde wordt nooit toegepast en blijft enkel auditmateriaal.
 * `filled = false` bij een uitdrukkelijk lege waarde (`''` telt niet als ingevuld, R-BMK-03).
 */
export type LinkBookmarkValueRow = {
  bookmarkName: string;
  label: string | null;
  dataType: string | null;
  valueText: string | null;
  previousValueText: string | null;
  declared: boolean;
  required: boolean;
  filled: boolean;
  filledAt: string | null;
  filledBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
};

/**
 * be.dda.catalogimport.service.LinkBookmarkValueService.LinkBookmarkValues (S1-F3) — leesmodel van
 * `GET /links/{linkId}/bookmark-values`. `lockedByOpenBatch` is de UI-spiegel van het slot: zolang de
 * koppeling een open batch heeft, weigert elke wijziging met 409
 * `LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH`.
 */
export type LinkBookmarkValues = {
  importLinkId: number;
  importLinkCode: string;
  activeRevisionId: number | null;
  lockedByOpenBatch: boolean;
  values: LinkBookmarkValueRow[];
  missingRequiredNames: string[];
};

/**
 * be.dda.catalogimport.web.CatalogImportLinkController.SetBookmarkValueRequest (S1-F3) — body van
 * `PUT /links/{linkId}/bookmark-values/{name}`. `value: ''` is een uitdrukkelijk lege waarde en wordt
 * bewaard; een ontbrekend veld wordt geweigerd (R-BMK-03). `updatedBy` is sinds 5A-6 een optionele
 * controle tegen de aangemelde gebruiker.
 */
export type SetBookmarkValueRequest = {
  value: string;
  updatedBy: string | null;
};

/**
 * De `placeKind`-waarden (be.dda.catalogimport.domain.BookmarkUsagePlace) die een `LINK_*`-veld van het
 * materialisatieverzoek vullen. Declareert een bookmark zo'n plaats, dan is die bookmark het invoerveld
 * en blijft het requestveld leeg — één bron per waarde (ontwerp §4 D7).
 */
export const BOOKMARK_PLACE_LINK_SUPPLIER_ORGANISATION = 'LINK_SUPPLIER_ORGANISATION';
export const BOOKMARK_PLACE_LINK_LIBRARY_CODE = 'LINK_LIBRARY_CODE';
export const BOOKMARK_PLACE_LINK_SEARCH_SUPPLIER = 'LINK_SEARCH_SUPPLIER';

/** `valueScope`-waarden van be.dda.catalogimport.domain.BookmarkValueScope. */
export const BOOKMARK_SCOPE_DEFINITION = 'DEFINITION';
export const BOOKMARK_SCOPE_LINK = 'LINK';
