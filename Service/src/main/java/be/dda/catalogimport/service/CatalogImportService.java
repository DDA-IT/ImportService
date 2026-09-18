package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.*;
import be.dda.catalogimport.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class CatalogImportService {
    private final CatalogSourceRepository sources; private final ImportDefinitionRepository definitions; private final ImportBatchRepository batches;
    private final CandidateOfferRepository candidates; private final ImportMutationRepository mutations; private final LibraryOfferRepository offers;
    public CatalogImportService(CatalogSourceRepository sources, ImportDefinitionRepository definitions, ImportBatchRepository batches, CandidateOfferRepository candidates, ImportMutationRepository mutations, LibraryOfferRepository offers) {
        this.sources = sources; this.definitions = definitions; this.batches = batches; this.candidates = candidates; this.mutations = mutations; this.offers = offers;
    }
    @Transactional public CatalogSource createSource(String code, String name) { return sources.save(new CatalogSource(required(code, "code"), required(name, "name"))); }
    @Transactional public ImportDefinition createDefinition(Long sourceId, int version, String libraryCode, String supplierColumn, String referenceColumn, String groupColumn, String priceColumn) {
        ImportDefinition d = new ImportDefinition(); d.source = sources.findById(sourceId).orElseThrow(() -> new IllegalArgumentException("Unknown source")); d.version = version; d.libraryCode = required(libraryCode, "libraryCode"); d.supplierColumn = required(supplierColumn, "supplierColumn"); d.referenceColumn = required(referenceColumn, "referenceColumn"); d.groupColumn = required(groupColumn, "groupColumn"); d.priceColumn = required(priceColumn, "priceColumn"); return definitions.save(d);
    }
    @Transactional public ImportBatch uploadAndScreen(Long definitionId, String fileName, byte[] content) {
        ImportDefinition definition = definitions.findById(definitionId).orElseThrow(() -> new IllegalArgumentException("Unknown definition"));
        String hash = sha256(content); Optional<ImportBatch> existing = batches.findByDefinitionIdAndContentHash(definitionId, hash); if (existing.isPresent()) return existing.get();
        ImportBatch batch = new ImportBatch(); batch.definition = definition; batch.contentHash = hash; batch.originalFileName = required(fileName, "fileName"); batch.originalContent = content; batches.save(batch);
        List<String> lines = Arrays.asList(new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        if (lines.isEmpty() || lines.getFirst().isBlank()) return block(batch, "CSV has no header");
        char delimiter = delimiter(lines.getFirst()); Map<String,Integer> columns = header(lines.getFirst(), delimiter);
        for (String requiredColumn : List.of(definition.supplierColumn, definition.referenceColumn, definition.groupColumn, definition.priceColumn)) if (!columns.containsKey(requiredColumn)) return block(batch, "Required CSV column missing: " + requiredColumn);
        Set<String> identities = new HashSet<>();
        for (int index = 1; index < lines.size(); index++) { String line = lines.get(index); if (line.isBlank()) continue; batch.recordCount++; String[] row = parse(line, delimiter); try {
            String supplier = value(row, columns, definition.supplierColumn); String reference = value(row, columns, definition.referenceColumn); String group = value(row, columns, definition.groupColumn); BigDecimal price = decimal(value(row, columns, definition.priceColumn));
            if (!identities.add(supplier + "\u0000" + reference)) throw new IllegalArgumentException("Duplicate offer identity");
            CandidateOffer candidate = new CandidateOffer(); candidate.batch = batch; candidate.supplierCode = supplier; candidate.supplierReference = reference; candidate.groupCode = group; candidate.price = price; candidate.sourceLine = index + 1; candidates.save(candidate);
        } catch (IllegalArgumentException invalid) { batch.issueCount++; }
        }
        if (batch.issueCount > 0) return block(batch, batch.issueCount + " invalid or duplicate record(s)");
        batch.status = ImportStatus.SCREENED; batches.save(batch); plan(batch); return batch;
    }
    @Transactional public ImportBatch approveAndPublish(Long batchId, String approver) {
        ImportBatch batch = batch(batchId); if (batch.status != ImportStatus.PLANNED) throw new IllegalStateException("Only planned batches can be approved"); if (required(approver, "approver").equals("system")) throw new IllegalArgumentException("A named user must approve publication");
        batch.status = ImportStatus.APPROVED; batches.save(batch);
        for (ImportMutation mutation : mutations.findByBatchId(batchId)) { CandidateOffer c = mutation.candidate; Optional<LibraryOffer> found = offers.findByLibraryCodeAndSupplierCodeAndSupplierReference(batch.definition.libraryCode, c.supplierCode, c.supplierReference);
            LibraryOffer offer = found.orElseGet(LibraryOffer::new); offer.libraryCode = batch.definition.libraryCode; offer.supplierCode = c.supplierCode; offer.supplierReference = c.supplierReference; offer.groupCode = c.groupCode; offer.price = c.price; offer.active = true; offer.updatedAt = Instant.now(); offers.save(offer); }
        batch.status = ImportStatus.PUBLISHED; return batches.save(batch);
    }
    @Transactional public ImportBatch plan(Long batchId) { return plan(batch(batchId)); }
    private ImportBatch plan(ImportBatch batch) {
        if (batch.status != ImportStatus.SCREENED) throw new IllegalStateException("Only screened batches can be planned"); long creates = 0;
        for (CandidateOffer candidate : candidates.findByBatchId(batch.id)) { Optional<LibraryOffer> current = offers.findByLibraryCodeAndSupplierCodeAndSupplierReference(batch.definition.libraryCode, candidate.supplierCode, candidate.supplierReference); MutationType type = current.isEmpty() ? MutationType.CREATE : MutationType.UPDATE;
            if (type == MutationType.CREATE) creates++; else if (same(current.get(), candidate)) continue;
            ImportMutation mutation = new ImportMutation(); mutation.batch = batch; mutation.candidate = candidate; mutation.type = type; mutation.previousPrice = current.map(o -> o.price).orElse(null); mutations.save(mutation); }
        long scope = offers.findByLibraryCode(batch.definition.libraryCode).size();
        if (scope > 0 && (creates > 100 || creates * 100 > scope)) return block(batch, "Bulk creation incident: " + creates + " new offers exceeds configured threshold");
        batch.status = ImportStatus.PLANNED; return batches.save(batch);
    }
    private ImportBatch block(ImportBatch batch, String reason) { batch.status = ImportStatus.BLOCKED; batches.save(batch); return batch; }
    private ImportBatch batch(Long id) { return batches.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown batch")); }
    private static boolean same(LibraryOffer offer, CandidateOffer candidate) { return offer.active && offer.groupCode.equals(candidate.groupCode) && offer.price.compareTo(candidate.price) == 0; }
    private static Map<String,Integer> header(String csv, char delimiter) { Map<String,Integer> map = new HashMap<>(); String[] names = parse(csv, delimiter); for(int i=0;i<names.length;i++) map.put(names[i].trim(), i); return map; }
    private static String value(String[] row, Map<String,Integer> columns, String name) { int i = columns.get(name); if (i >= row.length) throw new IllegalArgumentException("Missing value"); return required(row[i], name); }
    private static char delimiter(String header) { return header.chars().filter(c -> c == ';').count() > header.chars().filter(c -> c == ',').count() ? ';' : ','; }
    private static String[] parse(String line, char delimiter) { List<String> fields = new ArrayList<>(); StringBuilder field = new StringBuilder(); boolean quoted = false; for (int i = 0; i < line.length(); i++) { char c = line.charAt(i); if (c == '"') { if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append(c); i++; } else quoted = !quoted; } else if (c == delimiter && !quoted) { fields.add(field.toString()); field.setLength(0); } else field.append(c); } if (quoted) throw new IllegalArgumentException("Unclosed quoted CSV field"); fields.add(field.toString()); return fields.toArray(String[]::new); }
    private static BigDecimal decimal(String raw) { try { BigDecimal d = new BigDecimal(raw.replace(',', '.')); if (d.scale() > 6) throw new IllegalArgumentException("Price scale exceeds 6"); return d.setScale(6, RoundingMode.HALF_UP); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid decimal"); } }
    private static String required(String value, String field) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + field); return value.trim(); }
    private static String sha256(byte[] input) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(input); StringBuilder out = new StringBuilder(); for(byte b : digest) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception e) { throw new IllegalStateException("Cannot calculate content hash", e); } }
}
