package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import java.util.*;
import datapotter.datahelper.DataHelper_I;
import datapotter.datahelper.Field_I;
import datapotter.datahelper.DataField;
import datapotter.datahelper.EnumField;
import datapotter.datahelper.EnumListField;
import datapotter.datahelper.Field;
import datapotter.datahelper.LinkField;
import datapotter.datahelper.LinkListField;
import datapotter.datahelper.LinkMapField;
import datapotter.datahelper.ListDataField;
import datapotter.datahelper.MapDataField;

/**
 * Utility class for initializing ArcadeDB document schemas with TRUE ZERO REFLECTION.
 * Uses static metadata from generated code for type-safe schema registration.
 *
 * <p>NO INSTANCES CREATED - All type information flows through static Field_I objects
 * from generated _A classes, passed through SchemaBuilder/TypeDef.
 *
 * <p>Handles schema creation, field creation, index setup, and automatic
 * dependency-ordered registration of embedded types.
 *
 * <p>Example usage:
 * <pre>
 * InitDoc.initDocTypes(database,
 *     EmployeeDTO.TYPEDEF,  // Automatically registers AddressDTO, PhoneNumberDTO
 *     CompanyDTO.TYPEDEF
 * );
 * </pre>
 */
public class InitDoc {

    /**
     * The custom attribute a type or property's stable identity (PRP-28 phase 2) is stored under.
     * An opaque string as far as this class is concerned — never parsed, never validated, compared
     * only by equality, so a future change to the id FORMAT (owned by the annotation processors) can
     * never strand a database already carrying ids in the old one.
     */
    static final String ID_KEY = "datapotter.id";

    /** Marks a property no longer declared in source. Never dropped — see {@link #markOrphans()}. */
    static final String ORPHANED_AT_KEY = "datapotter.orphanedAt";

    private final DocumentType documentType;
    private final Class<?> clzz;
    private final Database db;
    private final List<Field_I<?, ?>> fields;

    // Property identity (PRP-28 phase 2) — captured ONCE, at construction, before this run creates
    // anything. This is what makes plan-then-apply possible at all: reading the recorded schema
    // after eagerly creating a property would already have produced the duplicate a rename must not.
    private final Map<String, Property> recordedPropsById;
    private final Map<String, Property> recordedPropsByName;
    private final Set<String> touchedPropertyNames = new HashSet<>();

    // Property renames detected this run but not applied (PRP-28 phase 3 collects these into a
    // MigrationPlan; applying one rewrites every row, so it needs a backup, a journal and an
    // explicit flag — none of which schema init itself is willing to assume).
    private final List<PropertyRenamePlan> detectedRenames = new ArrayList<>();

    /** Property renames detected this run, matched by id, not yet applied. See {@link MigrationPlan}. */
    public List<PropertyRenamePlan> detectedPropertyRenames() {
        return List.copyOf(detectedRenames);
    }

    public InitDoc(Database db, DocumentType dt, Class<?> clzz, List<Field_I<?, ?>> fields) {
        this.db = db;
        this.documentType = dt;
        this.clzz = clzz;
        this.fields = fields;
        this.recordedPropsById = new HashMap<>();
        this.recordedPropsByName = new HashMap<>();
        for (Property p : dt.getProperties()) {
            recordedPropsByName.put(p.getName(), p);
            Object id = p.getCustomValue(ID_KEY);
            if (id instanceof String s) recordedPropsById.put(s, p);
        }
    }

    public DocumentType documentType() {
        return documentType;
    }

    public Class<?> clzz() {
        return clzz;
    }

    /**
     * Initialize multiple document types at once.
     * Automatically detects and registers embedded types in dependency order.
     * ZERO REFLECTION - uses static FIELDS metadata.
     *
     * <p>Everything SAFE happens here and always has (type rename via {@link Rename}, creation,
     * first-time id adoption, orphan marking). A property rename matched by id is NEVER applied by
     * this call — it rewrites every row, so it is only ever collected into the returned
     * {@link MigrationPlan} (PRP-28 phase 3), which nothing here calls {@link MigrationPlan#apply}
     * on. A caller that ignores the return value (every caller before phase 3 existed, and most
     * calls since) gets EXACTLY today's behaviour: detected, reported to stderr, never applied.
     *
     * @param db the database instance
     * @param typeDefinitions varargs of TypeDef objects
     * @return what was detected but not applied — empty when there is nothing pending
     */
    @SafeVarargs
    public static MigrationPlan initDocTypes(Database db, TypeDef<? extends DataHelper_I<?>>... typeDefinitions) {
        // Collect all types including embedded dependencies
        Set<Class<?>> allTypes = new LinkedHashSet<>();
        Map<Class<?>, TypeDef<?>> typeDefMap = new HashMap<>();
        Map<Class<?>, List<Field_I<?, ?>>> fieldsMap = new HashMap<>();

        for (var typeDef : typeDefinitions) {
            typeDefMap.put(typeDef.definition(), typeDef);
            // Get FIELDS from TypeDef - NO REFLECTION!
            List<Field_I<?, ?>> typeFields = getFieldsFromTypeDef(typeDef);
            if (!typeFields.isEmpty()) {
                fieldsMap.put(typeDef.definition(), typeFields);
                collectDependencies(typeDef.definition(), typeFields, allTypes, fieldsMap);
            }
        }

        List<PropertyRenamePlan> detected = new ArrayList<>();

        // Register types in dependency order (embedded types first)
        for (Class<?> clazz : allTypes) {
            TypeDef<?> typeDef = typeDefMap.get(clazz);
            List<Field_I<?, ?>> typeFields = fieldsMap.get(clazz);

            if (typeDef != null) {
                detected.addAll(initDocType(db, typeDef, typeFields).detectedPropertyRenames());
            } else {
                // Register embedded type without indexes (defaults to DOCUMENT)
                detected.addAll(initDocType(db, clazz, ArcadeType.DOCUMENT, typeFields).detectedPropertyRenames());
            }
        }

        return new MigrationPlan(detected);
    }

    /**
     * Get FIELDS list from TypeDef - ZERO REFLECTION!
     * The TypeDef (SchemaBuilder) already has the Field_I objects from generated code.
     */
    private static List<Field_I<?, ?>> getFieldsFromTypeDef(TypeDef<?> typeDef) {
        if (typeDef instanceof SchemaBuilder) {
            return ((SchemaBuilder<?>) typeDef).fieldObjects();
        }
        return Collections.emptyList();
    }

    /**
     * Recursively collect all embedded type dependencies using static FIELDS metadata.
     * Uses depth-first traversal to ensure embedded types are registered before their parents.
     * ZERO REFLECTION (after initial FIELDS access) - uses static field metadata.
     *
     * @param clazz the class to analyze
     * @param fields the FIELDS list from the class (e.g., EmployeeDTO_A.FIELDS)
     * @param collected the set to collect dependencies into
     * @param fieldsMap map to store FIELDS for each discovered type
     */
    private static void collectDependencies(Class<?> clazz, List<Field_I<?, ?>> fields,
                                           Set<Class<?>> collected, Map<Class<?>, List<Field_I<?, ?>>> fieldsMap) {
        if (clazz == null || fields == null) {
            return;
        }

        // Check if already processed
        if (collected.contains(clazz)) {
            return;
        }

        // Process all fields to find embedded types
        // Exhaustive over the sealed Field_I: no default, so a new descriptor added to that
        // hierarchy fails to compile HERE until somebody decides whether it carries a dependency.
        // That is the whole reason the hierarchy is sealed, and an instanceof chain with a silent
        // fall-through gave it away.
        for (Field_I<?, ?> field : fields) {
            switch (field) {
                case DataField<?, ?> dataField -> {
                    // Nested DataHelper object
                    Class<?> nestedType = dataField.type();
                    @SuppressWarnings("unchecked")
                    List<Field_I<?, ?>> nestedFields = (List<Field_I<?, ?>>) dataField.nestedFields();

                    if (!nestedFields.isEmpty()) {
                        fieldsMap.put(nestedType, nestedFields);
                        // Recursively collect nested dependencies first
                        collectDependencies(nestedType, nestedFields, collected, fieldsMap);
                    }
                }
                case ListDataField<?, ?> listField -> {
                    // List<DataHelper> field
                    Class<?> elementType = listField.elementType();
                    @SuppressWarnings("unchecked")
                    List<Field_I<?, ?>> elementFields = (List<Field_I<?, ?>>) listField.elementFields();

                    if (!elementFields.isEmpty()) {
                        fieldsMap.put(elementType, elementFields);
                        // Recursively collect element dependencies first
                        collectDependencies(elementType, elementFields, collected, fieldsMap);
                    }
                }
                case MapDataField<?, ?, ?> mapField -> {
                    // Map<K, DataHelper> field
                    Class<?> valueType = mapField.valueType();
                    @SuppressWarnings("unchecked")
                    List<Field_I<?, ?>> valueFields = (List<Field_I<?, ?>>) mapField.valueFields();

                    if (!valueFields.isEmpty()) {
                        fieldsMap.put(valueType, valueFields);
                        // Recursively collect value dependencies first
                        collectDependencies(valueType, valueFields, collected, fieldsMap);
                    }
                }
                case LinkField<?, ?> linkField -> {
                    // Reference (LINK) target — a separate record type that must exist before the owner
                    Class<?> targetType = linkField.type();
                    @SuppressWarnings("unchecked")
                    List<Field_I<?, ?>> targetFields = (List<Field_I<?, ?>>) linkField.targetFields();
                    if (!targetFields.isEmpty()) {
                        fieldsMap.put(targetType, targetFields);
                        collectDependencies(targetType, targetFields, collected, fieldsMap);
                    }
                }
                case LinkListField<?, ?> linkList -> {
                    Class<?> elementType = linkList.elementType();
                    @SuppressWarnings("unchecked")
                    List<Field_I<?, ?>> elementFields = (List<Field_I<?, ?>>) linkList.elementFields();
                    if (!elementFields.isEmpty()) {
                        fieldsMap.put(elementType, elementFields);
                        collectDependencies(elementType, elementFields, collected, fieldsMap);
                    }
                }
                case LinkMapField<?, ?, ?> linkMap -> {
                    Class<?> valueType = linkMap.valueType();
                    @SuppressWarnings("unchecked")
                    List<Field_I<?, ?>> valueFields = (List<Field_I<?, ?>>) linkMap.valueFields();
                    if (!valueFields.isEmpty()) {
                        fieldsMap.put(valueType, valueFields);
                        collectDependencies(valueType, valueFields, collected, fieldsMap);
                    }
                }
                // A scalar has no record type of its own to register first. An enum is a scalar:
                // it crosses the boundary as one string (its uuid or its name), so it needs no
                // schema type, and a list of them needs no element type.
                case EnumField<?, ?> enumField -> { }
                case EnumListField<?, ?> enumListField -> { }
                case Field<?, ?> plain -> { }
            }
        }

        // Add this class after all its dependencies
        collected.add(clazz);
    }

    /**
     * Initialize a single document type from a TypeDef.
     *
     * @param db the database instance
     * @param typeDef the type definition
     * @param fields the FIELDS list
     * @return InitDoc instance for further operations
     */
    public static <E extends DataHelper_I<E>>
            InitDoc initDocType(Database db, TypeDef<E> typeDef, List<Field_I<?, ?>> fields) {
        var docType = resolveOrCreateType(db, typeDef.definition().getSimpleName(),
                typeDef.arcadeType(), typeDef.typeId());
        var d = new InitDoc(db, docType, typeDef.definition(), fields);
        d.ensureFieldsFromList(fields);

        // Register LINK type fields (Phase 3)
        if (typeDef.linkFields() != null && !typeDef.linkFields().isEmpty()) {
            for (Field_I<E, ?> linkField : typeDef.linkFields()) {
                d.ensureLinkProperty(linkField.name());
            }
        }

        if (typeDef.uniqueIndexes() != null && !typeDef.uniqueIndexes().isEmpty()) {
            for (String[] uniqueIndex : typeDef.uniqueIndexes()) {
                d.ensureUniqueIndexOnProperties(uniqueIndex);
            }
        }

        if (typeDef.lsmIndexes() != null && !typeDef.lsmIndexes().isEmpty()) {
            for (var p : typeDef.lsmIndexes()) {
                d.ensureIndexOnProperties(p, Schema.INDEX_TYPE.LSM_TREE, false);
            }
        }

        if (typeDef.fullStringIndexes() != null && !typeDef.fullStringIndexes().isEmpty()) {
            for (var p : typeDef.fullStringIndexes()) {
                d.ensureIndexOnProperties(p, Schema.INDEX_TYPE.FULL_TEXT, false);
            }
        }

        return d;
    }

    /**
     * Initialize a document type from a class with specified ArcadeType.
     *
     * @param db the database instance
     * @param clazz the class defining the document type
     * @param arcadeType the type (DOCUMENT, VERTEX, or EDGE)
     * @param fields the FIELDS list
     * @return InitDoc instance for further operations
     */
    /**
     * Resolve the {@link DocumentType} a {@code TypeDef} refers to — by identity first, then by
     * name, creating it only if neither matches (PRP-28 phase 2, section 5/7 of {@code 28-prp.05}).
     *
     * <p>Reads the CURRENT schema fresh on every call rather than from a snapshot captured once at
     * the top of {@link #initDocTypes} — both are correct (the schema is an in-memory object graph;
     * see {@code 28-prp.06} section "you never query by custom attribute"), and reading fresh is
     * simpler and no more costly since each call only concerns one type. What matters, and IS
     * preserved here, is that this read happens BEFORE anything is created: {@code existsType} by
     * the DECLARED name is never consulted first, because that eager check is exactly what turns a
     * rename into a second, empty, orphan-holding type.
     *
     * @throws IllegalStateException if the declared id matches a type by NAME whose recorded id is a
     *                                DIFFERENT, non-null value — an edited or mistyped type id, which
     *                                is refused rather than guessed at (DP-MIG equivalent for types).
     */
    private static DocumentType resolveOrCreateType(Database db, String className, ArcadeType arcadeType, String typeId) {
        var schema = db.getSchema();

        DocumentType byId = null;
        if (typeId != null) {
            for (DocumentType t : schema.getTypes()) {
                if (typeId.equals(t.getCustomValue(ID_KEY))) { byId = t; break; }
            }
        }

        if (byId != null) {
            if (!byId.getName().equals(className)) {
                // Matched by id under a DIFFERENT name: a genuine rename. The bare engine rename is a
                // known data-integrity hazard on some ArcadeDB versions (silently drops every index),
                // so this goes through the safe wrapper — capture, drop, rename, rebuild.
                Rename.type(db, byId.getName(), className);
            }
            return schema.getType(className);
        }

        if (schema.existsType(className)) {
            DocumentType existing = schema.getType(className);
            Object recordedId = existing.getCustomValue(ID_KEY);
            if (typeId != null && recordedId == null) {
                existing.setCustomValue(ID_KEY, typeId); // first-time adoption
            } else if (typeId != null && !typeId.equals(recordedId)) {
                throw new IllegalStateException(
                        "Type '" + className + "' is declared with id '" + typeId + "' but the schema "
                                + "already records it under a DIFFERENT id ('" + recordedId + "'). This is "
                                + "the signature of an edited or mistyped @ArcadeData(uuid=...) — renaming "
                                + "is free, but editing an id is not, and doing so would orphan every "
                                + "property recorded against the old one. Restore the original id, or "
                                + "confirm the change deliberately.");
            }
            return existing;
        }

        DocumentType created = switch (arcadeType) {
            case VERTEX -> schema.createVertexType(className);
            case EDGE -> schema.createEdgeType(className);
            case DOCUMENT -> schema.createDocumentType(className);
        };
        if (typeId != null) created.setCustomValue(ID_KEY, typeId);
        return created;
    }

    public static InitDoc initDocType(Database db, Class<?> clazz, ArcadeType arcadeType, List<Field_I<?, ?>> fields) {
        var CLASS = clazz.getSimpleName();
        DocumentType docType;
        var schema = db.getSchema();

        if (schema.existsType(CLASS)) {
            docType = schema.getType(CLASS);
        } else {
            // Create appropriate type based on ArcadeType
            docType = switch (arcadeType) {
                case VERTEX -> schema.createVertexType(CLASS);
                case EDGE -> schema.createEdgeType(CLASS);
                case DOCUMENT -> schema.createDocumentType(CLASS);
            };
        }

        return new InitDoc(db, docType, clazz, fields);
    }

    /**
     * Initialize a document type from a class (defaults to DOCUMENT type).
     *
     * @param db the database instance
     * @param clazz the class defining the document type
     * @param fields the FIELDS list
     * @return InitDoc instance for further operations
     * @deprecated Use {@link #initDocType(Database, Class, ArcadeType, List)} to explicitly specify the type
     */
    @Deprecated
    public static InitDoc initDocType(Database db, Class<?> clazz, List<Field_I<?, ?>> fields) {
        return initDocType(db, clazz, ArcadeType.DOCUMENT, fields);
    }

    /**
     * Ensure all fields from FIELDS list exist in the schema.
     * ZERO REFLECTION - uses static field metadata.
     *
     * @param fieldsList list of Field_I objects
     * @return this InitDoc instance
     */
    public InitDoc ensureFieldsFromList(List<Field_I<?, ?>> fieldsList) {
        for (Field_I<?, ?> field : fieldsList) {
            ensureFieldFromMetadata(field);
        }
        markOrphans();
        return this;
    }

    /**
     * Ensure a field exists in the schema using Field_I metadata, identity-aware (PRP-28 phase 2).
     *
     * <p>Match by id first, then by name, then create — the fixed diff order the design requires so
     * partial adoption (some fields carry {@code @P}, some don't) degrades gracefully rather than
     * producing a confused half-state. ZERO REFLECTION - all type info comes from static field objects.
     *
     * @param field the Field_I object with metadata
     * @return the resolved Property — an existing one (possibly still under its OLD name; see below),
     *         or a newly created one
     * @throws IllegalStateException if the declared id belongs to a property already marked orphaned
     *         (a retired id reused, or a field restored mid-refactor — either way, not a rename), or
     *         if an EXISTING property of this name already records a DIFFERENT, non-null id (the
     *         signature of an edited or mistyped {@code @P})
     */
    public Property ensureFieldFromMetadata(Field_I<?, ?> field) {
        String fieldName = field.name();
        String stableId = field.stableId();

        if (stableId != null) {
            Property byId = recordedPropsById.get(stableId);
            if (byId != null) {
                if (byId.getCustomValue(ORPHANED_AT_KEY) != null) {
                    throw new IllegalStateException(
                            "Field '" + fieldName + "' is declared with id '" + stableId + "', but that "
                                    + "id belongs to a property this schema already marked ORPHANED "
                                    + "(formerly '" + byId.getName() + "'). A real rename keeps its id "
                                    + "continuously present, so a declared id landing on an orphan is "
                                    + "either a field restored after being commented out, or a retired id "
                                    + "reused for something new - neither is a rename, and both need a "
                                    + "deliberate decision rather than an automatic migration. Mint a "
                                    + "fresh id with 'datapotter-id new' if this is genuinely a new field.");
                }
                touchedPropertyNames.add(byId.getName());
                if (!byId.getName().equals(fieldName)) {
                    // Rename plus type change is not a rename (PRP-28's own rule) — refuse rather than
                    // guess, before this is ever added to a plan a phase-3 apply step would carry out.
                    Type expected = expectedStorageType(field);
                    if (byId.getType() != expected) {
                        throw new IllegalStateException(
                                "Field '" + fieldName + "' is declared with id '" + stableId
                                        + "', matching property '" + byId.getName() + "', but its stored "
                                        + "type (" + byId.getType() + ") differs from what '" + fieldName
                                        + "' would now store (" + expected + "). This is a data migration, "
                                        + "not a rename, and is refused rather than guessed at.");
                    }
                    // Rename DETECTED, not applied here. ArcadeDB has no property-rename primitive;
                    // applying this is the seven-statement recipe in PropertyMigration, collected into
                    // the MigrationPlan initDocTypes returns and run only under an explicit apply, after
                    // a backup, journalled step by step. The property keeps its CURRENT name until then -
                    // reporting is the safety this phase exists to add, over silently creating a second
                    // property beside an orphan.
                    System.err.println("NOTE: field '" + byId.getName() + "' appears renamed to '"
                            + fieldName + "' (matched by id '" + stableId + "'). Not applied here - see "
                            + "the returned MigrationPlan. It keeps its current name '" + byId.getName()
                            + "' in the schema for now.");
                    detectedRenames.add(new PropertyRenamePlan(
                            documentType.getName(), byId.getName(), fieldName, stableId));
                }
                return byId;
            }
        }

        touchedPropertyNames.add(fieldName);

        if (documentType.existsProperty(fieldName)) {
            Property existing = documentType.getProperty(fieldName);
            if (stableId != null) {
                Object recordedId = existing.getCustomValue(ID_KEY);
                if (recordedId == null) {
                    existing.setCustomValue(ID_KEY, stableId); // first-time adoption
                } else if (!stableId.equals(recordedId)) {
                    throw new IllegalStateException(
                            "Field '" + fieldName + "' is declared with id '" + stableId + "' but the "
                                    + "schema already records it under a DIFFERENT id ('" + recordedId
                                    + "'). This is the signature of an edited or mistyped @P - renaming a "
                                    + "field is free, but editing its id is not, and doing so would orphan "
                                    + "the column recorded under the old one. Restore the original id, or "
                                    + "confirm the change deliberately.");
                }
            }
            return existing;
        }

        Property created = createProperty(field, fieldName);
        if (stableId != null) created.setCustomValue(ID_KEY, stableId);
        return created;
    }

    /**
     * Any recorded property carrying an id that no declared field claimed this run is marked
     * orphaned — never dropped. A uuid vanishing from source is ambiguous (a deleted field, or one
     * merely commented out mid-refactor), so guessing would destroy data; a deliberate drop command
     * is the only sanctioned way to actually remove one.
     */
    private void markOrphans() {
        for (var entry : recordedPropsByName.entrySet()) {
            String name = entry.getKey();
            if (touchedPropertyNames.contains(name)) continue;
            Property p = entry.getValue();
            if (p.getCustomValue(ID_KEY) == null) continue;        // unidentified - not this design's business
            if (p.getCustomValue(ORPHANED_AT_KEY) != null) continue; // already marked
            p.setCustomValue(ORPHANED_AT_KEY, java.time.Instant.now().toString());
            System.err.println("NOTE: property '" + name + "' (id '" + p.getCustomValue(ID_KEY)
                    + "') is no longer declared in source. Marked orphaned; its data is retained. "
                    + "A deliberate drop command is required to actually remove it.");
        }
    }

    /**
     * The ArcadeDB {@link Type} a declared field would store as, WITHOUT creating anything — the
     * same decision {@link #createProperty} makes, pulled out so a rename can be checked for a
     * smuggled type change before it is ever added to a {@link MigrationPlan}. Deliberately
     * duplicates {@link #createProperty}'s case labels rather than sharing a single exhaustive
     * switch with it: both are exhaustive over the same sealed {@link Field_I}, so a new descriptor
     * fails to compile in BOTH places until someone decides what it stores, which is the property
     * this design has relied on since {@code collectDependencies}.
     */
    private static Type expectedStorageType(Field_I<?, ?> field) {
        return switch (field) {
            case DataField<?, ?> ignored -> Type.EMBEDDED;
            case ListDataField<?, ?> ignored -> Type.LIST;
            case MapDataField<?, ?, ?> ignored -> Type.MAP;
            case LinkField<?, ?> ignored -> Type.LINK;
            case LinkListField<?, ?> ignored -> Type.LIST;
            case LinkMapField<?, ?, ?> ignored -> Type.MAP;
            case EnumField<?, ?> ignored -> Type.STRING;
            case EnumListField<?, ?> ignored -> Type.LIST;
            case Field<?, ?> plain -> plain.type().isEnum() ? Type.STRING : Type.getTypeByClass(plain.type());
        };
    }

    /**
     * The actual property-creation switch, unchanged in shape from before PRP-28 phase 2 — identity
     * (matching, adoption, the orphan mark) is a layer around this, not a change to it.
     *
     * @return the newly created Property
     */
    private Property createProperty(Field_I<?, ?> field, String fieldName) {
        // Exhaustive over the sealed Field_I — see collectDependencies for why there is no default.
        return switch (field) {
            case DataField<?, ?> dataField -> {
                // Embedded DataHelper object
                String embeddedTypeName = dataField.type().getSimpleName();

                // Ensure embedded type is registered
                if (!documentType.getSchema().existsType(embeddedTypeName)) {
                    System.err.println("Warning: Embedded type " + embeddedTypeName +
                        " not registered. It should have been registered via dependency collection.");
                }

                Property p = documentType.createProperty(fieldName, Type.EMBEDDED);
                p.setOfType(embeddedTypeName);
                yield p;
            }
            case ListDataField<?, ?> listField -> {
                // List<DataHelper> field
                String elementTypeName = listField.elementType().getSimpleName();

                // Ensure embedded type is registered
                if (!documentType.getSchema().existsType(elementTypeName)) {
                    System.err.println("Warning: List element type " + elementTypeName +
                        " not registered. It should have been registered via dependency collection.");
                }

                Property p = documentType.createProperty(fieldName, Type.LIST);
                p.setOfType(elementTypeName);
                yield p;
            }
            case MapDataField<?, ?, ?> mapField -> {
                // Map<K, DataHelper> field
                String valueTypeName = mapField.valueType().getSimpleName();

                // Ensure embedded type is registered
                if (!documentType.getSchema().existsType(valueTypeName)) {
                    System.err.println("Warning: Map value type " + valueTypeName +
                        " not registered. It should have been registered via dependency collection.");
                }

                Property p = documentType.createProperty(fieldName, Type.MAP);
                p.setOfType(valueTypeName);
                yield p;
            }
            case LinkField<?, ?> linkField -> {
                // Reference (LINK) — stores the target's RID; constrain to the target type if registered.
                Property p = documentType.createProperty(fieldName, Type.LINK);
                String targetTypeName = linkField.type().getSimpleName();
                if (documentType.getSchema().existsType(targetTypeName)) {
                    p.setOfType(targetTypeName);
                }
                yield p;
            }
            // LIST/MAP of references — elements are RIDs (not embedded); left element/value-untyped.
            case LinkListField<?, ?> linkList -> documentType.createProperty(fieldName, Type.LIST);
            case LinkMapField<?, ?, ?> linkMap -> documentType.createProperty(fieldName, Type.MAP);

            // An enum crosses the boundary as one string (its uuid or its name) — ArcadeDB has no
            // concept of an arbitrary Java enum class, and createProperty(name, Class<?>) does not
            // recognize one. A list of them is a LIST of those strings, which is what a
            // List<String> already was, so the stored shape is unchanged.
            case EnumField<?, ?> enumField -> documentType.createProperty(fieldName, Type.STRING);
            case EnumListField<?, ?> enumListField -> documentType.createProperty(fieldName, Type.LIST);

            case Field<?, ?> plain -> plain.type().isEnum()
                    // A hand-written DataHelper_I can still hand us a plain Field over an enum type;
                    // generated code no longer does. isEnum() is a plain Class metadata bit (like
                    // isInterface()/isArray()), not reflection in the getEnumConstants() sense.
                    ? documentType.createProperty(fieldName, Type.STRING)
                    : documentType.createProperty(fieldName, plain.type());
        };
    }

    /**
     * Ensure a LINK property exists in the schema (Phase 3).
     * If it doesn't exist, create it with Type.LINK.
     *
     * <p>This is used for schema-only LINK references declared with $$prefix fields.
     *
     * @param fieldName the field name
     */
    private void ensureLinkProperty(String fieldName) {
        if (!documentType.existsProperty(fieldName)) {
            documentType.createProperty(fieldName, Type.LINK);
        }
    }

    /**
     * Create an index if it doesn't already exist.
     *
     * <p><b>Deferred, not thrown, if a named property does not exist yet.</b> This is the ordinary
     * case for a property named in a detected-but-not-applied rename (PRP-28 phase 3): the TypeDef
     * still declares the index under the field's NEW name, which the schema will not carry until
     * {@link MigrationPlan#apply} runs. Before phase 3 this could only mean a genuine declaration
     * error, so it threw; now it is also the normal in-between state of an unapplied migration, and
     * the index is simply created on the next run that finds the property present — exactly as it
     * would be for a brand new property that has not been created yet either.
     *
     * @param indexType the type of index to create
     * @param unique whether the index should be unique
     * @param propertyNames field names for the index
     */
    private void createIndexIfNotAlreadyThere(Schema.INDEX_TYPE indexType, boolean unique, String... propertyNames) {
        for (String p : propertyNames) {
            if (!documentType.existsProperty(p)) {
                System.err.println("NOTE: index on " + documentType.getName() + java.util.Arrays.toString(propertyNames)
                        + " deferred - property '" + p + "' does not exist yet (a pending property rename, "
                        + "or this property has not been created). It will be created once the property is.");
                return;
            }
        }
        documentType.getOrCreateTypeIndex(indexType, unique, propertyNames);
    }

    /**
     * Ensure a unique index exists on the specified properties.
     *
     * @param fieldNames field names for the unique index
     * @return this InitDoc instance
     */
    public InitDoc ensureUniqueIndexOnProperties(String[] fieldNames) {
        createIndexIfNotAlreadyThere(Schema.INDEX_TYPE.LSM_TREE, true, fieldNames);
        return this;
    }

    /**
     * Ensure an index exists on the specified properties.
     *
     * @param fieldNames field names for the index
     * @param indexType type of index to create
     * @param unique whether the index should be unique
     * @return this InitDoc instance
     */
    public InitDoc ensureIndexOnProperties(String[] fieldNames, Schema.INDEX_TYPE indexType, boolean unique) {
        createIndexIfNotAlreadyThere(indexType, unique, fieldNames);
        return this;
    }
}
