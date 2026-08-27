package xyz.jphil.arcadedb.datahelper;

import xyz.jphil.datahelper.DataHelper_I;
import xyz.jphil.datahelper.Field_I;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for creating TypeDef objects.
 * Provides a convenient API for defining document types with fields and indexes.
 *
 * <p>Example usage:
 * <pre>
 * TypeDef personType = defType(Person.class)
 *     .fields(PersonSymbols.FIELDS)
 *     .unique(PersonSymbols.$email)
 *     .lsmIndex(PersonSymbols.$lastName)
 *     .__();
 * </pre>
 *
 * @param <E> the DataHelper entity type
 */
public class SchemaBuilder<E extends DataHelper_I<E>> implements TypeDef<E> {

    private final Class<E> definition;
    private final ArcadeType arcadeType;
    private java.util.function.Supplier<E> factory;
    private List<String> fields = new ArrayList<>();
    private List<Field_I<E, ?>> fieldObjects = new ArrayList<>();  // Store actual Field_I objects
    private List<Field_I<E, ?>> linkFields = new ArrayList<>();  // LINK type fields ($$prefix)
    private List<String[]> uniqueIndexes = new ArrayList<>();
    private List<String[]> lsmIndexes = new ArrayList<>();
    private List<String[]> fullStringIndexes = new ArrayList<>();

    public SchemaBuilder(Class<E> definition, ArcadeType arcadeType) {
        this.definition = definition;
        this.arcadeType = arcadeType;
    }

    /**
     * Create LSM tree indexes for multiple single fields (type-safe).
     *
     * @param fields Field_I objects from this entity
     * @return this builder
     */
    @SafeVarargs
    public final SchemaBuilder<E> lsmIndexForEach(Field_I<E, ?>... fields) {
        for (Field_I<E, ?> field : fields) {
            this.lsmIndexes.add(new String[] { field.name() });
        }
        return this;
    }

    /**
     * Create LSM tree indexes for multiple single fields (string-based).
     *
     * @param ls field names
     * @return this builder
     */
    public SchemaBuilder<E> lsmIndexForEach(String... ls) {
        for (String l : ls) {
            this.lsmIndexes.add(new String[] { l });
        }
        return this;
    }

    /**
     * Create LSM tree index (can be composite, type-safe).
     *
     * @param fields Field_I objects from this entity
     * @return this builder
     */
    @SafeVarargs
    public final SchemaBuilder<E> lsmIndex(Field_I<E, ?>... fields) {
        this.lsmIndexes.add(Arrays.stream(fields).map(Field_I::name).toArray(String[]::new));
        return this;
    }

    /**
     * Create LSM tree index (can be composite, string-based).
     *
     * @param l field name(s)
     * @return this builder
     */
    public SchemaBuilder<E> lsmIndex(String... l) {
        this.lsmIndexes.add(l);
        return this;
    }

    /**
     * Create unique index (can be composite, type-safe).
     *
     * @param fields Field_I objects from this entity
     * @return this builder
     */
    @SafeVarargs
    public final SchemaBuilder<E> unique(Field_I<E, ?>... fields) {
        this.uniqueIndexes.add(Arrays.stream(fields).map(Field_I::name).toArray(String[]::new));
        return this;
    }

    /**
     * Create unique index (can be composite, string-based).
     *
     * @param l field name(s)
     * @return this builder
     */
    public SchemaBuilder<E> unique(String... l) {
        this.uniqueIndexes.add(l);
        return this;
    }

    /**
     * Create full-text search index (can be composite, type-safe).
     *
     * @param fields Field_I objects from this entity
     * @return this builder
     */
    @SafeVarargs
    public final SchemaBuilder<E> fullStringIndex(Field_I<E, ?>... fields) {
        this.fullStringIndexes.add(Arrays.stream(fields).map(Field_I::name).toArray(String[]::new));
        return this;
    }

    /**
     * Create full-text search index (can be composite, string-based).
     *
     * @param l field name(s)
     * @return this builder
     */
    public SchemaBuilder<E> fullStringIndex(String... l) {
        this.fullStringIndexes.add(l);
        return this;
    }

    /**
     * Add multiple fields at once (from Field_I list, type-safe).
     *
     * @param fields list of Field_I objects (e.g., TestPersonDTO_I.FIELDS)
     * @return this builder
     */
    public SchemaBuilder<E> fields(List<Field_I<E, ?>> fields) {
        this.fieldObjects.addAll(fields);  // Store Field_I objects
        fields.forEach(f -> this.fields.add(f.name()));  // Also keep string names for backwards compat
        return this;
    }

    /**
     * Add multiple fields at once (from field names).
     *
     * @param fields list of field names
     * @return this builder
     */
    public SchemaBuilder<E> fieldsFromNames(List<String> fields) {
        this.fields.addAll(fields);
        return this;
    }

    /**
     * Add a single field (type-safe).
     *
     * @param field Field_I object
     * @return this builder
     */
    public SchemaBuilder<E> field(Field_I<E, ?> field) {
        this.fields.add(field.name());
        return this;
    }

    /**
     * Add a single field (string-based).
     *
     * @param field field name
     * @return this builder
     */
    public SchemaBuilder<E> field(String field) {
        this.fields.add(field);
        return this;
    }

    /**
     * Static factory method to create a new SchemaBuilder with specified type.
     *
     * @param definition the class defining the document type
     * @param arcadeType the ArcadeDB type (DOCUMENT, VERTEX, or EDGE)
     * @param <E> the DataHelper entity type
     * @return new SchemaBuilder instance
     */
    public static <E extends DataHelper_I<E>> SchemaBuilder<E> defType(Class<E> definition, ArcadeType arcadeType) {
        return new SchemaBuilder<>(definition, arcadeType);
    }

    /**
     * Static factory method to create a new SchemaBuilder (defaults to DOCUMENT type).
     *
     * @param definition the class defining the document type
     * @param <E> the DataHelper entity type
     * @return new SchemaBuilder instance
     * @deprecated Use {@link #defType(Class, ArcadeType)} to explicitly specify the type
     */
    @Deprecated
    public static <E extends DataHelper_I<E>> SchemaBuilder<E> defType(Class<E> definition) {
        return new SchemaBuilder<>(definition, ArcadeType.DOCUMENT);
    }

    /**
     * Declare the type's zero-reflection factory — always {@code E::new}.
     *
     * <p>One line here removes the constructor reference from every read in the codebase:
     * {@code query(db, DriveFile.TYPEDEF)} instead of {@code query(db, DriveFile.TYPEDEF,
     * DriveFile::new)} at each call site.
     *
     * @param factory the entity's no-arg constructor reference
     * @return this builder
     */
    public SchemaBuilder<E> factory(java.util.function.Supplier<E> factory) {
        this.factory = factory;
        return this;
    }

    @Override
    public java.util.function.Supplier<E> factory() {
        return factory;
    }

    @Override
    public Class<E> definition() {
        return definition;
    }

    @Override
    public ArcadeType arcadeType() {
        return arcadeType;
    }

    @Override
    public List<String> fields() {
        return fields;
    }

    @Override
    public List<String[]> uniqueIndexes() {
        return uniqueIndexes;
    }

    @Override
    public List<String[]> lsmIndexes() {
        return lsmIndexes;
    }

    @Override
    public List<String[]> fullStringIndexes() {
        return fullStringIndexes;
    }

    /**
     * Get the Field_I objects (not just names).
     * Provides access to full field metadata including types.
     *
     * @return list of Field_I objects
     */
    @SuppressWarnings("unchecked")
    public List<Field_I<?, ?>> fieldObjects() {
        return (List<Field_I<?, ?>>) (List<?>) fieldObjects;
    }

    /**
     * Finalize the builder and return as TypeDef.
     * The __ method provides a nice visual terminator in fluent chains.
     *
     * @return this as TypeDef
     */
    public TypeDef<E> __() {
        return this;
    }

    // ========== PHASE 3: LINK TYPE SUPPORT (TODO) ==========

    /**
     * Add a LINK property to the schema.
     *
     * <p>Registers a field as a LINK reference that will be created with Type.LINK
     * in the schema during initialization.
     *
     * <p><b>Usage:</b>
     * <pre>
     * // In Order.java (user manually declares):
     * static DataField&lt;Order, Customer&gt; $$customerRef =
     *     new DataField&lt;&gt;("customerRef", Customer.class);
     *
     * public static TypeDef&lt;Order&gt; TYPEDEF =
     *     schemaBuilder()
     *         .addLinkType($$customerRef)
     *         .__();
     * </pre>
     *
     * <p><b>Design Notes:</b>
     * <ul>
     *   <li>Link fields use {@code $$} prefix (double dollar) by convention</li>
     *   <li>Not included in regular FIELDS list (schema-only declaration)</li>
     *   <li>Target type extracted from DataField generic parameter</li>
     *   <li>Creates Type.LINK property in schema during initDocType()</li>
     *   <li>Runtime RID management uses ArcadeDB API directly</li>
     * </ul>
     *
     * <p><b>Runtime Usage:</b>
     * <pre>
     * // Set LINK reference
     * order.set("customerRef", customer.getIdentity());
     *
     * // Get LINK reference
     * RID custRID = order.get("customerRef");
     * </pre>
     *
     * @param linkField the Field_I object representing the LINK reference
     * @return this builder for chaining
     */
    public SchemaBuilder<E> addLinkType(Field_I<E, ?> linkField) {
        this.linkFields.add(linkField);
        return this;
    }

    /**
     * Get the LINK fields registered via addLinkType().
     *
     * @return list of LINK field objects
     */
    public List<Field_I<E, ?>> linkFields() {
        return linkFields;
    }
}
