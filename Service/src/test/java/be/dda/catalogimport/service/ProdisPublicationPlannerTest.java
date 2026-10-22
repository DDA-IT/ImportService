package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.ProdisPublicationProjectionTest.row;
import static be.dda.catalogimport.service.ProdisPublicationProjectionTest.vkp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleDescriptionRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceDetailRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleRow;
import be.dda.catalogimport.service.ProdisPublicationPlanner.ArticleState;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Change;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Column;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Plan;
import be.dda.catalogimport.service.ProdisPublicationPlanner.PlanOutcome;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationProjection.Notice;
import be.dda.catalogimport.service.ProdisPublicationProjection.Severity;
import be.dda.catalogimport.service.ProdisPublicationProjectionTest.RowBuilder;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P2b (design {@code publicatie-prodis-db-direct-design.md} par. 2.4 planner-stap, 2.5, 3, 5, 6): {@link ProdisPublicationPlanner}
 * — elke uitkomst en melding, reconcile-by-state, Q1 = b (VKP + bruto herberekend), Q2, P2a punt 8
 * ({@code sales_price_last_updated} enkel bij een VKP-wijziging), deterministisch en idempotent (target opnieuw plannen →
 * UNCHANGED).
 * <p>
 * Standaard-Prodis: prijscode 0, bruto-politiek 1, basisprijs 100, VKP% 10/20/30/40/50 met prijzen 10..50, bruto 10 /
 * 0%, omschrijving "Oude omschrijving" — intern consistent volgens Prg_1185. Standaard-item: UPDATE 100 → 120 met
 * omschrijving.
 */
class ProdisPublicationPlannerTest {

    private static final CatalogArticleIdentity IDENTITY = new CatalogArticleIdentity("0601", "0601|GRP|K1", "050", "REF-1");
    private static final LocalDateTime LAST_UPDATED = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    // --- normaal scenario -------------------------------------------------------------------------------------------

    @Test
    void aChangedBasePriceRecalculatesSalesPricesAndBrutAndOverwritesTheDescription() {
        Plan plan = plan(r -> {
        }, p -> {
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.reasonCode()).isNull();
        assertThat(plan.mutationId()).isEqualTo(42L);
        assertThat(plan.requestHash()).matches("[0-9a-f]{64}");
        assertThat(plan.legacyMaster()).isEqualTo(LegacyMasterMode.NONE);
        assertThat(plan.prior().basePrice()).isEqualByComparingTo("100");
        assertThat(plan.prior().salesPriceLastUpdated()).isEqualTo(LAST_UPDATED);
        assertThat(plan.target().basePrice()).isEqualByComparingTo("120");
        assertThat(plan.target().prices()).containsExactlyEntriesOf(map(12d, 24d, 36d, 48d, 60d));
        assertThat(plan.target().percentages()).containsExactlyEntriesOf(map(10d, 20d, 30d, 40d, 50d));
        assertThat(plan.target().brutPrice()).isEqualTo(12d);
        assertThat(plan.target().brutPricePercentage()).isEqualTo(0d);
        assertThat(plan.target().description()).isEqualTo("Nieuwe omschrijving");
        assertThat(plan.target().salesPriceLastUpdated()).isNull();
        assertThat(plan.changes()).extracting(Change::column, Change::detailCode, Change::prior, Change::target)
                .containsExactly(
                        tuple(Column.BASE_PRICE, null, "100", "120"),
                        tuple(Column.BRUT_PRICE, null, "10", "12"),
                        tuple(Column.DETAIL_PRICE, 1, "10", "12"),
                        tuple(Column.DETAIL_PRICE, 2, "20", "24"),
                        tuple(Column.DETAIL_PRICE, 3, "30", "36"),
                        tuple(Column.DETAIL_PRICE, 4, "40", "48"),
                        tuple(Column.DETAIL_PRICE, 5, "50", "60"),
                        tuple(Column.DESCRIPTION, null, "Oude omschrijving", "Nieuwe omschrijving"));
        assertThat(plan.salesPriceLastUpdated()).isTrue();
        assertThat(plan.updatesPriceRow()).isTrue();
        assertThat(plan.detailCodesToUpdate()).containsExactly(1, 2, 3, 4, 5);
        assertThat(plan.updatesDescription()).isTrue();
        assertThat(codes(plan)).containsExactly("DESCRIPTION_OVERWRITTEN");
        assertThat(plan.changes().get(0).column().table()).isEqualTo("catalog_article_price");
        assertThat(plan.changes().get(0).column().column()).isEqualTo("base_price");
    }

    @Test
    void planningTwiceGivesTheSamePlan() {
        Plan first = plan(r -> r.prices.add(vkp(1, "12", "10", "10")), p -> {
        });
        Plan second = plan(r -> r.prices.add(vkp(1, "12", "10", "10")), p -> {
        });

        assertThat(second).isEqualTo(first);
    }

    @Test
    void replanningOnTheTargetStateIsUnchangedAndMovesNothing() {
        Plan applied = plan(r -> {
        }, p -> {
        });

        Plan retry = planOn(applied.target(), r -> {
        });

        assertThat(retry.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(retry.changes()).isEmpty();
        assertThat(retry.salesPriceLastUpdated()).isFalse();
        assertThat(retry.updatesPriceRow()).isFalse();
        assertThat(retry.updatesDescription()).isFalse();
        assertThat(codes(retry)).isEmpty();
    }

    @Test
    void replanningWithAppliedPercentagesWithManyDecimalsIsUnchanged() {
        Consumer<RowBuilder> item = r -> {
            r.specVersion = "2";
            r.after = new BigDecimal("123.456");
            r.prices.add(vkp(1, "15.24", "12.345678901234", "10"));
            r.prices.add(vkp(3, "41.15", "33.333333333333", "30"));
        };
        Plan applied = plan(item, p -> {
        });
        assertThat(applied.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);

        Plan retry = planOn(applied.target(), item);

        assertThat(retry.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(retry.changes()).isEmpty();
    }

    @Test
    void nothingToChangeIsUnchanged() {
        Plan plan = plan(r -> {
            r.after = new BigDecimal("100");
            r.description = "Oude omschrijving";
        }, p -> {
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.notices()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(LegacyMasterMode.class)
    void theLegacyMasterModeIsPassedOnAndNeverRefuses(LegacyMasterMode mode) {
        Plan plan = ProdisPublicationPlanner.plan(item(r -> {
        }), prodis(p -> {
        }), mode);

        assertThat(plan.legacyMaster()).isEqualTo(mode);
        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
    }

    @Test
    void anUnknownLegacyMasterModeIsUnknown() {
        Plan plan = ProdisPublicationPlanner.plan(item(r -> {
        }), prodis(p -> {
        }), null);

        assertThat(plan.legacyMaster()).isEqualTo(LegacyMasterMode.UNKNOWN);
    }

    // --- CREATE en door de projectie geblokkeerd ----------------------------------------------------------------------

    @Test
    void aCreateIsNotSentInV1() {
        Plan plan = ProdisPublicationPlanner.plan(item(r -> r.actionType = "CREATE"), null, LegacyMasterMode.NONE);

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.NOT_SENT);
        assertThat(plan.reasonCode()).isEqualTo("CREATE_NOT_SUPPORTED_IN_V1");
        assertThat(plan.prior()).isNull();
        assertThat(plan.target()).isNull();
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.salesPriceLastUpdated()).isFalse();
    }

    @Test
    void aCreateForAnExistingArticleSignalsTheFutureConflict() {
        Plan plan = ProdisPublicationPlanner.plan(item(r -> r.actionType = "CREATE"), prodis(p -> {
        }), LegacyMasterMode.NONE);

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.NOT_SENT);
        assertThat(plan.notices()).extracting(Notice::code, Notice::severity).containsExactly(
                tuple("ARTICLE_ALREADY_EXISTS", Severity.WARNING),
                tuple("CREATE_NOT_SUPPORTED_IN_V1", Severity.BLOCKING));
    }

    @Test
    void aCreateKeepsTheProjectionReasonsVisible() {
        Plan plan = ProdisPublicationPlanner.plan(item(r -> {
            r.actionType = "CREATE";
            r.currency = "USD";
        }), null, LegacyMasterMode.NONE);

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.NOT_SENT);
        assertThat(codes(plan)).containsExactly("CURRENCY_NOT_SUPPORTED_BY_PRODIS", "CREATE_NOT_SUPPORTED_IN_V1");
    }

    @Test
    void anItemBlockedByTheProjectionIsRejectedWithoutLookup() {
        Plan plan = ProdisPublicationPlanner.plan(item(r -> r.currency = "USD"), null, LegacyMasterMode.NONE);

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.REJECTED);
        assertThat(plan.reasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED_BY_PRODIS");
        assertThat(plan.changes()).isEmpty();
    }

    // --- omschrijving > 4000 tekens (P5b; Prodis varchar(4000), nooit afkappen) ----------------------------------------

    @Test
    void aDescriptionOfExactly4000CharactersIsWritten() {
        Plan plan = plan(r -> r.description = "x".repeat(4000), p -> {
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.target().description()).hasSize(4000);
    }

    @Test
    void aDescriptionLongerThan4000CharactersIsRejectedWithoutLookupAndNeverTruncated() {
        Item item = item(r -> r.description = "x".repeat(4001));

        Plan withoutLookup = ProdisPublicationPlanner.plan(item, null, LegacyMasterMode.NONE);
        Plan withLookup = ProdisPublicationPlanner.plan(item, prodis(p -> {
        }), LegacyMasterMode.NONE);

        assertRejected(withoutLookup, "DESCRIPTION_TOO_LONG");
        assertThat(withLookup).isEqualTo(withoutLookup);
        assertThat(withoutLookup.prior()).isNull();
        assertThat(withoutLookup.target()).isNull();
        assertThat(withoutLookup.notices().get(withoutLookup.notices().size() - 1).message()).contains("4001")
                .contains("never truncated");
    }

    @Test
    void theDescriptionLimitCountsCharactersNotUtf16Units() {
        // 4000 tekens buiten het BMP = 8000 UTF-16-eenheden: PostgreSQL varchar(4000) telt tekens, dus dit past.
        String astral = new String(Character.toChars(0x1F600)).repeat(4000);

        assertThat(plan(r -> r.description = astral, p -> {
        }).outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertRejected(ProdisPublicationPlanner.plan(item(r -> r.description = astral + "x"), null, LegacyMasterMode.NONE),
                "DESCRIPTION_TOO_LONG");
    }

    @Test
    void aLongDescriptionThatIsKeptIsNoProblem() {
        assertThat(ProdisPublicationPlanner.planWithoutProdis(item(r -> {
            r.description = null;
            r.descriptionState = "EMPTY";
        }), LegacyMasterMode.NONE)).isNull();
    }

    @Test
    void planWithoutProdisDecidesOnlyWhatNeedsNoProdisState() {
        assertThat(ProdisPublicationPlanner.planWithoutProdis(item(r -> {
        }), LegacyMasterMode.NONE)).isNull();
        assertThat(ProdisPublicationPlanner.planWithoutProdis(item(r -> r.actionType = "CREATE"), LegacyMasterMode.NONE)
                .outcome()).isEqualTo(PlanOutcome.NOT_SENT);
        assertThat(ProdisPublicationPlanner.planWithoutProdis(item(r -> r.currency = "USD"), LegacyMasterMode.NONE)
                .reasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED_BY_PRODIS");
        assertThat(ProdisPublicationPlanner.planWithoutProdis(item(r -> r.description = "y".repeat(5000)),
                LegacyMasterMode.PRODIS)).satisfies(plan -> {
                    assertThat(plan.reasonCode()).isEqualTo("DESCRIPTION_TOO_LONG");
                    assertThat(plan.legacyMaster()).isEqualTo(LegacyMasterMode.PRODIS);
                });
    }

    @Test
    void anUpdateWithoutLookupOrWithAnotherIdentityIsAProgrammingError() {
        Item item = item(r -> {
        });

        assertThatThrownBy(() -> ProdisPublicationPlanner.plan(item, null, LegacyMasterMode.NONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never be planned as 'not found'");
        CatalogArticleLookup other = new CatalogArticleLookup(new CatalogArticleIdentity("0601", "0601|GRP|K1", "050",
                "REF-2"), 1, List.of());
        assertThatThrownBy(() -> ProdisPublicationPlanner.plan(item, other, LegacyMasterMode.NONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another identity");
    }

    // --- identiteit ---------------------------------------------------------------------------------------------------

    @Test
    void identityProblemsAreRejected() {
        assertRejected(plan(r -> {
        }, p -> p.supplierCount = 2), "SUPPLIER_AMBIGUOUS");
        assertRejected(plan(r -> {
        }, p -> p.supplierCount = 0), "ARTICLE_NOT_FOUND");
        assertRejected(plan(r -> {
        }, p -> p.articleCount = 0), "ARTICLE_NOT_FOUND");
        assertRejected(plan(r -> {
        }, p -> p.articleCount = 2), "ARTICLE_AMBIGUOUS");
    }

    @Test
    void anAmbiguousSupplierWinsOverAnyArticleCount() {
        assertRejected(plan(r -> {
        }, p -> {
            p.supplierCount = 2;
            p.articleCount = 2;
        }), "SUPPLIER_AMBIGUOUS");
    }

    // --- vorm ---------------------------------------------------------------------------------------------------------

    @Test
    void unsupportedRowShapesAreRejected() {
        assertShape(p -> p.articleActive = false, "inactive or deleted");
        assertShape(p -> p.articleDeleted = true, "inactive or deleted");
        assertShape(p -> p.priceRows = 0, "0 price rows");
        assertShape(p -> p.priceRows = 2, "2 price rows");
        assertShape(p -> p.priceActive = false, "price row is inactive");
        assertShape(p -> p.priceDeleted = true, "price row is inactive");
        assertShape(p -> p.details.add(detail("6", 1d, 1d)), "code '6'");
        assertShape(p -> p.details.add(detail("1", 1d, 1d)), "more than once");
        assertShape(p -> p.details.remove(4), "exactly 1..5");
        assertShape(p -> p.details.set(2, new CatalogArticlePriceDetailRow("3", 30d, 30d, false, false)),
                "detail 3 is inactive");
        assertShape(p -> p.details.set(2, new CatalogArticlePriceDetailRow("3", 30d, 30d, true, true)),
                "detail 3 is inactive");
        assertShape(p -> p.descriptionPresent = false, "0 NL descriptions");
        assertShape(p -> p.extraDescriptions.add(new CatalogArticleDescriptionRow("Tweede", true, false)),
                "2 NL descriptions");
        assertShape(p -> p.descriptionActive = false, "description is inactive or deleted");
        assertShape(p -> p.descriptionDeleted = true, "description is inactive or deleted");
    }

    @Test
    void keepingAnEmptyProdisBasePriceIsRejectedInsteadOfCalculatingWithZero() {
        Plan plan = plan(r -> r.after = BigDecimal.ZERO, p -> p.basePrice = null);

        assertRejected(plan, "PRODIS_ROW_SHAPE_UNSUPPORTED");
        assertThat(plan.prior()).isNotNull();
        assertThat(plan.notices().get(plan.notices().size() - 1).message()).contains("base_price is empty");
    }

    // --- prijscode (P2a) ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"3", "", "A"})
    void anUnsupportedPriceCodeIsRejectedWithThePrior(String priceCode) {
        Plan plan = plan(r -> {
        }, p -> p.priceCode = priceCode);

        assertRejected(plan, "PRICE_CODE_UNSUPPORTED");
        assertThat(plan.prior()).isNotNull();
        assertThat(plan.target()).isNull();
    }

    @Test
    void aMissingPriceCodeIsRejected() {
        assertRejected(plan(r -> {
        }, p -> p.priceCode = null), "PRICE_CODE_UNSUPPORTED");
    }

    @Test
    void priceCode2RecalculatesWithTheExistingPercentagesAndDoesNotGuardThem() {
        Plan plan = plan(r -> {
            r.specVersion = "2";
            // Prodis-percentage 2 (20) wijkt af van expected (15): niet bewaakt, want niet geschreven.
            r.prices.add(vkp(2, "30", "25", "15"));
        }, p -> p.priceCode = "2");

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.target().percentages()).containsExactlyEntriesOf(map(10d, 20d, 30d, 40d, 50d));
        assertThat(plan.target().prices()).containsExactlyEntriesOf(map(12d, 24d, 36d, 48d, 60d));
        assertThat(plan.notices()).extracting(Notice::code, Notice::field)
                .contains(tuple("PERCENTAGES_NOT_APPLICABLE", "VKP2_PCT"));
        assertThat(codes(plan)).doesNotContain("VALUE_CHANGED_IN_PRODIS", "PRICE_ROUNDING_DIFFERENCE");
    }

    @Test
    void priceCode1LeavesSalesPricesAndDoesNotMoveSalesPriceLastUpdated() {
        Plan plan = plan(r -> {
        }, p -> {
            p.priceCode = "1";
            p.brutPrice = 9d;
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.target().prices()).containsExactlyEntriesOf(map(10d, 20d, 30d, 40d, 50d));
        // Politiek 1 (P2a punt 2): bruto := bestaande VKP1, bruto% := 0.
        assertThat(plan.changes()).extracting(Change::column).containsExactly(Column.BASE_PRICE, Column.BRUT_PRICE,
                Column.DESCRIPTION);
        assertThat(plan.target().brutPrice()).isEqualTo(10d);
        assertThat(plan.salesPriceLastUpdated()).isFalse();
        assertThat(plan.updatesPriceRow()).isTrue();
    }

    @Test
    void onlyABrutChangeDoesNotMoveSalesPriceLastUpdated() {
        // Prijscode 1 (VKP-prijzen ongemoeid), politiek 0: na een echte basisprijswijziging 100 -> 120 wordt bruto
        // basis x AVP% (5%) = 6 i.p.v. 10; geen VKP-prijs wijzigt, dus sales_price_last_updated schuift niet.
        Plan plan = plan(r -> {
            r.descriptionState = "NOT_MAPPED";
            r.description = null;
        }, p -> {
            p.priceCode = "1";
            p.brutPricePolitics = "0";
            p.brutPricePercentage = 5d;
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.changes()).extracting(Change::column, Change::prior, Change::target).containsExactly(
                tuple(Column.BASE_PRICE, "100", "120"), tuple(Column.BRUT_PRICE, "10", "6"));
        assertThat(plan.salesPriceLastUpdated()).isFalse();
        assertThat(plan.updatesPriceRow()).isTrue();
        assertThat(plan.detailCodesToUpdate()).isEmpty();
        assertThat(plan.derivedDrift()).isEmpty();
        assertThat(codes(plan)).doesNotContain("DERIVED_PRICE_DRIFT");
    }

    @Test
    void aMissingProdisPercentageIsCalculatedAsZeroButReported() {
        Plan plan = plan(r -> {
        }, p -> p.details.set(3, detail("4", null, 40d)));

        assertThat(plan.target().prices().get(4)).isEqualTo(0d);
        assertThat(plan.notices()).extracting(Notice::code, Notice::field, Notice::severity)
                .contains(tuple("PERCENTAGE_MISSING", "VKP4_PCT", Severity.WARNING));
    }

    // --- reconcile-by-state -------------------------------------------------------------------------------------------

    @Test
    void aBasePriceThatIsNeitherExpectedNorTargetIsAConflictAndWritesNothing() {
        Plan plan = plan(r -> {
        }, p -> p.basePrice = new BigDecimal("105"));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.CONFLICT);
        assertThat(plan.reasonCode()).isEqualTo("VALUE_CHANGED_IN_PRODIS");
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.salesPriceLastUpdated()).isFalse();
        assertThat(plan.target()).isNotNull();
        assertThat(plan.notices()).extracting(Notice::code, Notice::field, Notice::severity)
                .contains(tuple("VALUE_CHANGED_IN_PRODIS", "BASE_PRICE", Severity.BLOCKING));
    }

    @Test
    void anEmptyProdisBasePriceIsAConflictForAWrittenBasePrice() {
        Plan plan = plan(r -> {
        }, p -> p.basePrice = null);

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.CONFLICT);
    }

    @Test
    void aProdisBasePriceEqualToTheTargetIsAllowed() {
        Plan plan = plan(r -> {
        }, p -> p.basePrice = new BigDecimal("120.000000"));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.changes()).extracting(Change::column).doesNotContain(Column.BASE_PRICE);
    }

    @Test
    void aKeptBasePriceIsNotGuarded() {
        Plan plan = plan(r -> {
            r.after = BigDecimal.ZERO;
            r.description = "Oude omschrijving";
        }, p -> p.basePrice = new BigDecimal("100"));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(codes(plan)).containsExactly("ZERO_KEEPS_EXISTING");
    }

    @Test
    void aWrittenPercentageIsGuardedAgainstExpectedAndTarget() {
        Consumer<RowBuilder> item = r -> {
            r.specVersion = "2";
            r.prices.add(vkp(2, "30", "25", "20"));
        };
        Plan fromExpected = plan(item, p -> {
        });
        Plan fromTarget = plan(item, p -> p.details.set(1, detail("2", 25d, 20d)));
        Plan changed = plan(item, p -> p.details.set(1, detail("2", 22d, 20d)));
        Plan empty = plan(item, p -> p.details.set(1, detail("2", null, 20d)));

        assertThat(fromExpected.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(fromExpected.changes()).extracting(Change::column, Change::detailCode, Change::prior, Change::target)
                .contains(tuple(Column.DETAIL_PERCENTAGE, 2, "20", "25"), tuple(Column.DETAIL_PRICE, 2, "20", "30"));
        assertThat(fromTarget.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(fromTarget.changes()).extracting(Change::column).doesNotContain(Column.DETAIL_PERCENTAGE);
        assertThat(changed.outcome()).isEqualTo(PlanOutcome.CONFLICT);
        assertThat(changed.notices()).extracting(Notice::code, Notice::field)
                .contains(tuple("VALUE_CHANGED_IN_PRODIS", "VKP2_PCT"));
        assertThat(empty.outcome()).isEqualTo(PlanOutcome.CONFLICT);
    }

    @Test
    void theDoubleInProdisIsComparedExactlyWithTheExpectedDecimal() {
        Consumer<RowBuilder> item = r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "42", "35", "33.330000000000"));
        };
        Plan equal = plan(item, p -> p.details.set(0, detail("1", 33.33d, 33.33d)));
        Plan offByOneUlp = plan(item, p -> p.details.set(0, detail("1", Math.nextUp(33.33d), 33.33d)));

        assertThat(equal.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(equal.changes()).extracting(Change::column, Change::detailCode, Change::prior, Change::target)
                .contains(tuple(Column.DETAIL_PERCENTAGE, 1, "33.33", "35"));
        assertThat(codes(equal)).doesNotContain("VALUE_CHANGED_IN_PRODIS");
        assertThat(offByOneUlp.outcome()).isEqualTo(PlanOutcome.CONFLICT);
    }

    @Test
    void allConflictingFieldsAreReported() {
        Plan plan = plan(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "12", "11", "10"));
            r.prices.add(vkp(5, "60", "55", "50"));
        }, p -> {
            p.basePrice = new BigDecimal("101");
            p.details.set(0, detail("1", 9d, 9d));
            p.details.set(4, detail("5", 51d, 51d));
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.CONFLICT);
        assertThat(plan.notices()).filteredOn(n -> n.code().equals("VALUE_CHANGED_IN_PRODIS"))
                .extracting(Notice::field).containsExactly("BASE_PRICE", "VKP1_PCT", "VKP5_PCT");
    }

    @Test
    void theDescriptionIsNeverAConflict() {
        Plan different = plan(r -> {
        }, p -> p.description = "Door iemand gewijzigd");
        Plan same = plan(r -> r.description = "Oude omschrijving", p -> {
        });
        Plan kept = plan(r -> {
            r.description = "";
            r.descriptionState = "EMPTY";
        }, p -> {
        });

        assertThat(different.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(different.notices()).extracting(Notice::code, Notice::severity)
                .containsExactly(tuple("DESCRIPTION_OVERWRITTEN", Severity.WARNING));
        assertThat(same.changes()).extracting(Change::column).doesNotContain(Column.DESCRIPTION);
        assertThat(codes(same)).doesNotContain("DESCRIPTION_OVERWRITTEN");
        assertThat(kept.target().description()).isEqualTo("Oude omschrijving");
        assertThat(kept.changes()).extracting(Change::column).doesNotContain(Column.DESCRIPTION);
    }

    // --- afronding en afgeleide prijzen -------------------------------------------------------------------------------

    @Test
    void aSourceAmountThatDiffersFromTheCalculatedPriceIsAWarningAndTheCalculatedValueIsWritten() {
        Plan plan = plan(r -> {
            r.specVersion = "2";
            r.after = new BigDecimal("100");
            r.prices.add(vkp(1, "33.333", "33.333", "10"));
            r.prices.add(vkp(2, "33.33", "33.33", "20"));
        }, p -> {
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.target().prices().get(1)).isEqualTo(33.33d);
        assertThat(plan.target().prices().get(2)).isEqualTo(33.33d);
        assertThat(plan.notices()).filteredOn(n -> n.code().equals("PRICE_ROUNDING_DIFFERENCE"))
                .extracting(Notice::field, Notice::severity).containsExactly(tuple("VKP1", Severity.WARNING));
    }

    @Test
    void noRoundingWarningForAKeptPercentage() {
        Plan plan = plan(r -> r.prices.add(vkp(1, "99.99", "10", "10")), p -> {
        });

        assertThat(codes(plan)).doesNotContain("PRICE_ROUNDING_DIFFERENCE");
        assertThat(codes(plan)).contains("EXPECTED_NOT_AVAILABLE");
    }

    @Test
    void withoutOwnPriceChangeDerivedPricesAreNeverWrittenButTheDriftIsShown() {
        // Basisprijs 0 -> KEEP (Q2), geen percentages: het item schrijft zelf geen prijs. VKP3 (31) en bruto (politiek
        // 0, 5% -> 5) kloppen niet met de eigen basis en percentages van Prodis.
        Plan plan = plan(r -> {
            r.after = BigDecimal.ZERO;
            r.description = "Oude omschrijving";
        }, p -> {
            p.details.set(2, detail("3", 30d, 31d));
            p.brutPricePolitics = "0";
            p.brutPricePercentage = 5d;
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.salesPriceLastUpdated()).isFalse();
        assertThat(plan.updatesPriceRow()).isFalse();
        assertThat(plan.detailCodesToUpdate()).isEmpty();
        assertThat(plan.derivedDrift()).extracting(Change::column, Change::detailCode, Change::prior, Change::target)
                .containsExactly(tuple(Column.BRUT_PRICE, null, "10", "5"), tuple(Column.DETAIL_PRICE, 3, "31", "30"));
        // De target is de toestand na toepassing: afgeleide kolommen = Prodis.
        assertThat(plan.target().prices().get(3)).isEqualTo(31d);
        assertThat(plan.target().brutPrice()).isEqualTo(10d);
        assertThat(plan.target().brutPricePercentage()).isEqualTo(5d);
        assertThat(plan.notices()).extracting(Notice::code, Notice::severity).containsExactly(
                tuple("ZERO_KEEPS_EXISTING", Severity.WARNING), tuple("DERIVED_PRICE_DRIFT", Severity.WARNING));
        assertThat(plan.notices().get(1).message()).contains("brut_price 10 -> 5", "price 3 31 -> 30");

        Plan retry = planOn(plan.target(), r -> {
            r.after = BigDecimal.ZERO;
            r.description = "Oude omschrijving";
        });
        assertThat(retry.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(retry.derivedDrift()).isEqualTo(plan.derivedDrift());
    }

    @Test
    void withoutOwnPriceChangeOnlyTheDescriptionIsWritten() {
        Plan plan = plan(r -> r.after = BigDecimal.ZERO, p -> p.details.set(2, detail("3", 30d, 31d)));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.changes()).extracting(Change::column).containsExactly(Column.DESCRIPTION);
        assertThat(plan.updatesPriceRow()).isFalse();
        assertThat(plan.salesPriceLastUpdated()).isFalse();
        assertThat(codes(plan)).containsExactly("ZERO_KEEPS_EXISTING", "DESCRIPTION_OVERWRITTEN", "DERIVED_PRICE_DRIFT");
    }

    @Test
    void anUnchangedWrittenBasePriceIsNoOwnChange() {
        // Basisprijs VALUE 100 = Prodis 100: geen echte wijziging, dus ook geen afgeleide prijzen.
        Plan plan = plan(r -> {
            r.after = new BigDecimal("100");
            r.description = "Oude omschrijving";
        }, p -> p.details.set(2, detail("3", 30d, 31d)));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(plan.derivedDrift()).extracting(Change::detailCode).containsExactly(3);
    }

    @Test
    void withoutOwnPriceChangeAMissingPercentageIsNotReportedAsRecalculated() {
        Plan plan = plan(r -> {
            r.after = BigDecimal.ZERO;
            r.description = "Oude omschrijving";
        }, p -> p.details.set(3, detail("4", null, 40d)));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.UNCHANGED);
        assertThat(plan.target().prices().get(4)).isEqualTo(40d);
        assertThat(codes(plan)).containsExactly("ZERO_KEEPS_EXISTING", "DERIVED_PRICE_DRIFT");
    }

    @Test
    void aChangedBasePriceWritesTheDerivedPricesAlsoWhereProdisDrifted() {
        Plan plan = plan(r -> {
        }, p -> p.details.set(2, detail("3", 30d, 31d)));

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.changes()).extracting(Change::column, Change::detailCode, Change::prior, Change::target)
                .contains(tuple(Column.BASE_PRICE, null, "100", "120"), tuple(Column.DETAIL_PRICE, 3, "31", "36"),
                        tuple(Column.BRUT_PRICE, null, "10", "12"));
        assertThat(plan.salesPriceLastUpdated()).isTrue();
        assertThat(plan.derivedDrift()).isEmpty();
        assertThat(codes(plan)).doesNotContain("DERIVED_PRICE_DRIFT");
    }

    @Test
    void aChangedPercentageWithKeptBasePriceWritesTheDerivedPrices() {
        Plan plan = plan(r -> {
            r.specVersion = "2";
            r.after = BigDecimal.ZERO;
            r.description = "Oude omschrijving";
            r.prices.add(vkp(2, "25", "25", "20"));
        }, p -> {
        });

        assertThat(plan.outcome()).isEqualTo(PlanOutcome.WOULD_UPDATE);
        assertThat(plan.changes()).extracting(Change::column, Change::detailCode, Change::prior, Change::target)
                .containsExactly(tuple(Column.DETAIL_PERCENTAGE, 2, "20", "25"), tuple(Column.DETAIL_PRICE, 2, "20", "25"));
        assertThat(plan.salesPriceLastUpdated()).isTrue();
        assertThat(plan.derivedDrift()).isEmpty();
    }

    // --- hulp ---------------------------------------------------------------------------------------------------------

    private static void assertRejected(Plan plan, String code) {
        assertThat(plan.outcome()).isEqualTo(PlanOutcome.REJECTED);
        assertThat(plan.reasonCode()).isEqualTo(code);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.salesPriceLastUpdated()).isFalse();
        assertThat(plan.notices()).extracting(Notice::code, Notice::severity).contains(tuple(code, Severity.BLOCKING));
    }

    private static void assertShape(Consumer<ProdisBuilder> change, String reason) {
        Plan plan = plan(r -> {
        }, change);
        assertRejected(plan, "PRODIS_ROW_SHAPE_UNSUPPORTED");
        assertThat(plan.notices().get(plan.notices().size() - 1).message()).contains(reason);
    }

    private static List<String> codes(Plan plan) {
        return plan.notices().stream().map(Notice::code).toList();
    }

    private static Item item(Consumer<RowBuilder> change) {
        return ProdisPublicationProjection.project(row(change));
    }

    private static Plan plan(Consumer<RowBuilder> itemChange, Consumer<ProdisBuilder> prodisChange) {
        return ProdisPublicationPlanner.plan(item(itemChange), prodis(prodisChange), LegacyMasterMode.NONE);
    }

    /** Plant het item opnieuw op een Prodis die exact de gegeven toestand bevat (zoals de writer ze zou schrijven). */
    private static Plan planOn(ArticleState state, Consumer<RowBuilder> itemChange) {
        return ProdisPublicationPlanner.plan(item(itemChange), prodis(p -> {
            p.priceCode = state.priceCode();
            p.brutPricePolitics = state.brutPricePolitics();
            p.basePrice = state.basePrice();
            p.brutPrice = state.brutPrice();
            p.brutPricePercentage = state.brutPricePercentage();
            p.description = state.description();
            p.details.clear();
            for (int code = 1; code <= 5; code++) {
                p.details.add(detail(Integer.toString(code), state.percentages().get(code), state.prices().get(code)));
            }
        }), LegacyMasterMode.NONE);
    }

    private static CatalogArticleLookup prodis(Consumer<ProdisBuilder> change) {
        ProdisBuilder builder = new ProdisBuilder();
        change.accept(builder);
        return builder.build();
    }

    private static CatalogArticlePriceDetailRow detail(String code, Double percentage, Double price) {
        return new CatalogArticlePriceDetailRow(code, percentage, price, true, false);
    }

    private static Map<Integer, Double> map(double... values) {
        Map<Integer, Double> map = new TreeMap<>();
        for (int i = 0; i < values.length; i++) {
            map.put(i + 1, values[i]);
        }
        return map;
    }

    /** Een intern consistente Prodis-rij (Prg_1185) voor {@link #IDENTITY}. */
    static final class ProdisBuilder {
        int supplierCount = 1;
        int articleCount = 1;
        boolean articleActive = true;
        boolean articleDeleted;
        int priceRows = 1;
        boolean priceActive = true;
        boolean priceDeleted;
        String priceCode = "0";
        String brutPricePolitics = "1";
        BigDecimal basePrice = new BigDecimal("100.000000");
        Double brutPrice = 10d;
        Double brutPricePercentage = 0d;
        String description = "Oude omschrijving";
        List<CatalogArticlePriceDetailRow> details = new ArrayList<>(List.of(detail("1", 10d, 10d), detail("2", 20d, 20d),
                detail("3", 30d, 30d), detail("4", 40d, 40d), detail("5", 50d, 50d)));
        boolean descriptionPresent = true;
        boolean descriptionActive = true;
        boolean descriptionDeleted;
        List<CatalogArticleDescriptionRow> extraDescriptions = new ArrayList<>();

        CatalogArticleLookup build() {
            List<CatalogArticleDescriptionRow> descriptionRows = new ArrayList<>();
            if (descriptionPresent) {
                descriptionRows.add(new CatalogArticleDescriptionRow(description, descriptionActive, descriptionDeleted));
            }
            descriptionRows.addAll(extraDescriptions);
            List<CatalogArticlePriceRow> prices = new ArrayList<>();
            for (int i = 0; i < priceRows; i++) {
                prices.add(new CatalogArticlePriceRow(priceCode, brutPricePolitics, basePrice, brutPrice,
                        brutPricePercentage, LAST_UPDATED, priceActive, priceDeleted, details));
            }
            List<CatalogArticleRow> articles = new ArrayList<>();
            for (int i = 0; i < articleCount; i++) {
                articles.add(new CatalogArticleRow(articleActive, articleDeleted, prices, descriptionRows));
            }
            return new CatalogArticleLookup(IDENTITY, supplierCount, articles);
        }
    }
}
