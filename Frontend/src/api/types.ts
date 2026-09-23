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

// be.dda.catalogimport.service.BundleDecisionService.DecisionFilter (request)
export type DecisionFilter = {
  batchId?: number;
  status?: MutationStatus;
  statusReason?: string;
  actionType?: MutationActionType;
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
