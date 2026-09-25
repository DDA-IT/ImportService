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
export type ImportLinkRow = {
  id: number;
  code: string;
  name: string;
  supplierCode: string;
  supplierName: string;
  libraryCode: string;
  active: boolean;
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
