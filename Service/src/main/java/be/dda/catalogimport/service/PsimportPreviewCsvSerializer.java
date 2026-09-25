package be.dda.catalogimport.service;

import be.dda.catalogimport.service.PsimportPreviewMapper.Field;
import be.dda.catalogimport.service.PsimportPreviewMapper.Row;
import be.dda.catalogimport.service.PsimportPreviewService.PsimportPreview;
import java.io.IOException;
import java.io.StringWriter;

/**
 * Pure CSV-serialisatie van een PSIMPORT-preview voor een bevroren bundel (beslissing 2026-09-25, slice 1).
 * Geen database, geen Spring: rechtstreeks unit-testbaar.
 * <p>
 * RFC 4180-escaping: komma, dubbele quote, newline in velden → ingesloten in dubbele quotes met interne quotes
 * verdubbd.
 * <p>
 * Bescherming tegen CSV-formule-injectie: een waarde die begint met =, +, -, of @ krijgt een apostrof
 * voorafgaand (werkbaar in Excel en Google Sheets zonder fouten). Dit is defensief: de state zou al
 * NOT_MAPPED/UNKNOWN/... zijn voor nieuw gegenereerde velden, maar de mutatie-velden kunnen theoretisch
 * een gemapt formuleveld dragen.
 */
public final class PsimportPreviewCsvSerializer {

    private PsimportPreviewCsvSerializer() {
    }

    /**
     * Serialiseert een PsimportPreview naar CSV.
     * <p>
     * Output format:
     * <ul>
     *   <li>Regel 1: banner met # aan het begin, previewOnly, contractStatus, previewSpecVersion, bundleContentHash</li>
     *   <li>Regel 2: headers (batchId, mutationId, actionType, complete, dan per veld: CODE, CODE.state)</li>
     *   <li>Regel 3+: data-rijen</li>
     * </ul>
     *
     * @param preview de PsimportPreview om te serialiseren
     * @return CSV-tekst (UTF-8)
     */
    public static String toCsv(PsimportPreview preview) {
        try (StringWriter out = new StringWriter()) {
            writeBanner(out, preview);
            writeHeaders(out, preview);
            writeRows(out, preview);
            return out.toString();
        } catch (IOException e) {
            // StringWriter gooit geen IOException, maar we vangen het af voor de signatuur.
            throw new RuntimeException("Unexpected IO error during CSV generation", e);
        }
    }

    private static void writeBanner(StringWriter out, PsimportPreview preview) throws IOException {
        // Banner: # PREVIEW previewOnly=true contractStatus=... previewSpecVersion=... bundleContentHash=...
        out.write("# PREVIEW previewOnly=true contractStatus=");
        out.write(preview.contractStatus());
        out.write(" previewSpecVersion=");
        out.write(preview.previewSpecVersion());
        out.write(" bundleContentHash=");
        String hash = preview.bundleContentHash();
        if (hash != null) {
            out.write(hash);
        } else {
            out.write("(null)");
        }
        out.write("\n");
    }

    private static void writeHeaders(StringWriter out, PsimportPreview preview) throws IOException {
        // Headers: batchId, mutationId, actionType, complete, dan per veld: CODE, CODE.state
        out.write("batchId,mutationId,actionType,complete");
        if (!preview.content().isEmpty()) {
            Row firstRow = preview.content().get(0);
            for (Field field : firstRow.fields()) {
                out.write(",");
                writeEscapedCsv(out, field.code());
                out.write(",");
                writeEscapedCsv(out, field.code() + ".state");
            }
        }
        out.write("\n");
    }

    private static void writeRows(StringWriter out, PsimportPreview preview) throws IOException {
        for (Row row : preview.content()) {
            out.write(String.valueOf(row.batchId()));
            out.write(",");
            out.write(String.valueOf(row.mutationId()));
            out.write(",");
            writeEscapedCsv(out, row.actionType());
            out.write(",");
            out.write(row.complete() ? "true" : "false");

            for (Field field : row.fields()) {
                out.write(",");
                writeEscapedCsv(out, field.value());
                out.write(",");
                writeEscapedCsv(out, field.state().toString());
            }
            out.write("\n");
        }
    }

    /**
     * Schrijft een CSV-veld met RFC 4180-escaping en bescherming tegen formule-injectie.
     * <p>
     * Regels:
     * <ul>
     *   <li>Null → legen (leeg veld)</li>
     *   <li>Begint met =, +, -, @ → voorafgaand apostrof</li>
     *   <li>Bevat ",\n,\r → ingesloten in dubbele quotes, interne quotes verdubbeld</li>
     *   <li>Overige → as-is (geen escaping nodig)</li>
     * </ul>
     *
     * @param out  de StringWriter om in te schrijven
     * @param value de waarde (mag null zijn)
     */
    private static void writeEscapedCsv(StringWriter out, String value) throws IOException {
        if (value == null) {
            // Lege waarde
            return;
        }

        // Bescherming tegen formule-injectie: apostrof voorafgaan als begint met =, +, -, @
        String escaped = value;
        if (!value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == '+' || value.charAt(0) == '-' || value.charAt(0) == '@')) {
            escaped = "'" + value;
        }

        // RFC 4180: als het veld komma, quote, of newline bevat, moet het ingesloten zijn in quotes en interne quotes verdubbeld
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            out.write("\"");
            out.write(escaped.replace("\"", "\"\""));
            out.write("\"");
        } else {
            out.write(escaped);
        }
    }
}
