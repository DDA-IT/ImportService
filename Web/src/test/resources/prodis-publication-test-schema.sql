-- Testkopie van de Prodis-tabellen die de publicatie leest of bijwerkt (slice P5a; docs/design/
-- publicatie-prodis-db-direct-design.md par. 2.3 en 6 "Veilige testopzet"). ENKEL voor de lokale testdatabase
-- prodis_pubtest; opgebouwd door ProdisPublicationTestDatabase als eigenaar catalog_import.
--
-- Bron: de eindvorm van de Prodis-Liquibase (C:\javaProject\prodis\Dao\src\main\resources\config\liquibase\changelog:
-- 20260114074917 language, 20260114074920 supplier, 20260114074921 + 20260519120000 supplier_group, 20260418235900/01,
-- 20260421100000/01, 20260422150000, 20260423120000, 20260423130000/01, 20260427120000/130000, 20260518151000,
-- 20260522120000/130000, 20260612120000, 20260831100000, 20260902100000, 20260929121000, 20260929150000, 20260930120000,
-- 20261008100000), overgenomen uit een alleen-lezen schema-dump (pg_dump --schema-only) van de lokale Prodis-database op
-- Liquibase-stand 20261022100000 (2026-10-22). Kolommen, types, defaults, NOT NULL, primaire sleutels, unieke
-- constraints, indexen en de foreign keys BINNEN deze negen tabellen zijn letterlijk; de foreign keys naar tabellen
-- buiten deze set (general_ledger, article_group, article, unit_of_measure, vat_code, city, currency,
-- payment_condition, payment_method, customer_supplier_status, catalog_import_profile) zijn bewust weggelaten (die
-- tabellen bestaan hier niet; de publicatie raakt die kolommen niet). Geen triggers (ook niet in Prodis).
-- De twee sequences bestaan enkel om te bewijzen dat de rol er GEEN gebruik van mag maken.
-- ProdisSchemaParityTest (-Dprodis.schemaParity=true) vergelijkt deze kopie alleen-lezend met de echte Prodis.

-- Guard: dit script verwijdert tabellen; het weigert in elke andere database dan prodis_pubtest.
do $$
begin
    if current_database() <> 'prodis_pubtest' then
        raise exception 'prodis-publication-test-schema.sql may only run in prodis_pubtest, not in %', current_database();
    end if;
end
$$;

drop table if exists catalog_article_description, catalog_article_price_detail, catalog_article_price, catalog_article,
    system_settings, language, supplier_catalog, supplier_group, supplier cascade;
drop sequence if exists sequence_generator, sequencegenerator;

create sequence sequence_generator start with 1050 increment by 50 no minvalue no maxvalue cache 1;
create sequence sequencegenerator start with 1 increment by 1 no minvalue no maxvalue cache 1;

create table supplier (
    id bigint not null,
    number character varying(255),
    name_alfa character varying(255) not null,
    name character varying(255) not null,
    register_of_commerce character varying(255),
    phone character varying(255),
    fax character varying(255),
    mobile character varying(255),
    vat_country character varying(255),
    vat character varying(255),
    term_of_payment character varying(255),
    shipment_condition character varying(255),
    credit_limit integer,
    responsible character varying(255),
    memo character varying(255),
    payment_discount numeric(21,2),
    min_order_qty integer,
    min_order_price numeric(21,2),
    city_id bigint,
    currency_id bigint,
    language_id bigint,
    payment_condition_id bigint,
    payment_method_id bigint,
    status_id bigint,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255),
    payment_advice_enabled boolean default false not null,
    split_purchase_order_by_depot boolean default false not null
);

create table supplier_group (
    id bigint not null,
    group_name character varying(255),
    discount_code character varying(255),
    purchase_percentage double precision,
    brut_percentage double precision,
    supplier_id bigint,
    article_group_id bigint,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255),
    legacy_code character varying(80)
);

create table supplier_catalog (
    id bigint not null,
    catalog_name character varying(255) not null,
    scope_key character varying(255) not null,
    imported_at timestamp without time zone not null,
    source_filename character varying(255),
    checksum character varying(255),
    valid_from date,
    valid_to date,
    status character varying(255) not null,
    supplier_id bigint not null,
    import_profile_id bigint,
    legacy_number character varying(255),
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255)
);

create table language (
    id bigint not null,
    code character varying(255) not null,
    txt character varying(255) not null,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255)
);

create table system_settings (
    id bigint not null,
    legacy_master character varying(16) default 'PRODIS'::character varying not null,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default now() not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255)
);

create table catalog_article (
    id bigint not null,
    supplier_barcode character varying(255),
    supplier_id bigint not null,
    supplier_reference character varying(255) not null,
    supplier_reference_stripped character varying(255) not null,
    article_type character varying(255),
    statistical_code character varying(255),
    country_of_origin character varying(255),
    assortment character varying(255),
    genre_code character varying(255),
    conversion_factor numeric(21,6),
    order_quantity numeric(21,6),
    weight numeric(21,6),
    volume numeric(21,6),
    supplier_catalog_id bigint not null,
    replaced_by_article_id bigint,
    invoice_unit_id bigint,
    sales_unit_id bigint,
    purchase_unit_id bigint,
    account_nr_sales_id bigint,
    account_nr_purchase_id bigint,
    vat_id bigint,
    article_group_id bigint,
    supplier_group_id bigint not null,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255),
    legacy_bib_id character varying(255),
    legacy_internal_id character varying(255),
    article_id bigint
);

create table catalog_article_price (
    id bigint not null,
    base_price numeric(21,6),
    catalog_article_id bigint not null,
    price_code character varying(255),
    brut_price_politics character varying(255),
    purchase_price double precision,
    purchase_percentage double precision,
    brut_price double precision,
    brut_price_percentage double precision,
    sales_price_last_updated timestamp without time zone,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255)
);

create table catalog_article_price_detail (
    id bigint not null,
    code character varying(255) not null,
    percentage double precision,
    price double precision,
    catalog_article_price_id bigint,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255)
);

create table catalog_article_description (
    id bigint not null,
    language_id bigint not null,
    description character varying(4000) not null,
    default_language boolean default false not null,
    catalog_article_id bigint not null,
    created_by character varying(255) default 'system'::character varying not null,
    created_date timestamp without time zone default current_timestamp not null,
    last_modified_by character varying(255),
    last_modified_date timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by character varying(255),
    record_active boolean default true not null,
    tenant_id character varying(255)
);

alter table only catalog_article_description add constraint catalog_article_description_pkey primary key (id);
alter table only catalog_article add constraint catalog_article_pkey primary key (id);
alter table only catalog_article_price_detail add constraint catalog_article_price_detail_pkey primary key (id);
alter table only catalog_article_price add constraint catalog_article_price_pkey primary key (id);
alter table only language add constraint language_pkey primary key (id);
alter table only supplier_catalog add constraint supplier_catalog_pkey primary key (id);
alter table only supplier_group add constraint supplier_group_pkey primary key (id);
alter table only supplier add constraint supplier_pkey primary key (id);
alter table only system_settings add constraint system_settings_pkey primary key (id);
alter table only catalog_article add constraint ux_catalog_article__article_id unique (article_id);
alter table only catalog_article add constraint ux_catalog_article__supplier_key
    unique (supplier_catalog_id, supplier_id, supplier_reference, supplier_reference_stripped, supplier_group_id);
alter table only catalog_article_description add constraint ux_catalog_article_description__article_language
    unique (catalog_article_id, language_id);
alter table only catalog_article_price add constraint ux_catalog_article_price__catalog_article_id
    unique (catalog_article_id);

create index idx_catalog_article__active_supplier_barcode_lower_trgm on catalog_article
    using gin (lower((supplier_barcode)::text) gin_trgm_ops) where (record_active = true);
create index idx_catalog_article__active_supplier_reference_lower_trgm on catalog_article
    using gin (lower((supplier_reference)::text) gin_trgm_ops) where (record_active = true);
create index idx_catalog_article__active_supplier_reference_stripped_lower_t on catalog_article
    using gin (lower((supplier_reference_stripped)::text) gin_trgm_ops) where (record_active = true);
create index idx_catalog_article__article_group_id on catalog_article using btree (article_group_id);
create index idx_catalog_article__legacy_bib_id on catalog_article using btree (legacy_bib_id)
    where (legacy_bib_id is not null);
create index idx_catalog_article__legacy_bib_internal_id on catalog_article using btree (legacy_bib_id, legacy_internal_id);
create index idx_catalog_article__supplier_catalog on catalog_article using btree (supplier_id, supplier_catalog_id);
create index idx_catalog_article__supplier_group_id on catalog_article using btree (supplier_group_id);
create index idx_catalog_article_description__catalog_article_language on catalog_article_description
    using btree (catalog_article_id, language_id);
create index idx_catalog_article_description__default_description_lower_trgm on catalog_article_description
    using gin (lower((description)::text) gin_trgm_ops) where (default_language = true);
create index idx_catalog_article_price__catalog_article_id on catalog_article_price using btree (catalog_article_id);
create index idx_catalog_article_price_detail__catalog_article_price_code on catalog_article_price_detail
    using btree (catalog_article_price_id, code);
create index idx_supplier__number_coalesced on supplier using btree (coalesce(number, ''::character varying));
create index idx_supplier_catalog__supplier_scope_status on supplier_catalog
    using btree (supplier_id, catalog_name, scope_key, status);
create index idx_supplier_group__supplier_article_group_id on supplier_group using btree (supplier_id, article_group_id, id);
create unique index ux_supplier_catalog_active_scope on supplier_catalog using btree (supplier_id, catalog_name, scope_key)
    where ((status)::text = 'ACTIVE'::text);

alter table only catalog_article add constraint fk_catalog_article__replaced_by_article_id
    foreign key (replaced_by_article_id) references catalog_article(id);
alter table only catalog_article add constraint fk_catalog_article__supplier_catalog_id
    foreign key (supplier_catalog_id) references supplier_catalog(id);
alter table only catalog_article add constraint fk_catalog_article__supplier_group_id
    foreign key (supplier_group_id) references supplier_group(id);
alter table only catalog_article add constraint fk_catalog_article__supplier_id
    foreign key (supplier_id) references supplier(id);
alter table only catalog_article_description add constraint fk_catalog_article_description__catalog_article_id
    foreign key (catalog_article_id) references catalog_article(id);
alter table only catalog_article_description add constraint fk_catalog_article_description__language_id
    foreign key (language_id) references language(id);
alter table only catalog_article_price add constraint fk_catalog_article_price__catalog_article_id
    foreign key (catalog_article_id) references catalog_article(id);
alter table only catalog_article_price_detail add constraint fk_catalog_article_price_detail__catalog_article_price_id
    foreign key (catalog_article_price_id) references catalog_article_price(id);
alter table only supplier add constraint fk_supplier__language_id
    foreign key (language_id) references language(id);
alter table only supplier_catalog add constraint fk_supplier_catalog__supplier_id
    foreign key (supplier_id) references supplier(id);
alter table only supplier_group add constraint fk_supplier_group__supplier_id
    foreign key (supplier_id) references supplier(id);
