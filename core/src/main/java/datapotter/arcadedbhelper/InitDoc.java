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

    private final DocumentType documentType;
    private final Class<?> clzz;
    private final Database db;
    private final List<Field_I<?, ?>> fields;

    public InitDoc(Database db, DocumentType dt, Class<?> clzz, List<Field_I<?, ?>> fields) {
        this.db = db;
        this.documentType = dt;
        this.clzz = clzz;
        this.fields = fields;
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
     * @param db the database instance
     * @param typeDefinitions varargs of TypeDef objects
     */
    @SafeVarargs
    public static void initDocTypes(Database db, TypeDef<? extends DataHelper_I<?>>... typeDefinitions) {
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

        // Register types in dependency order (embedded types first)
        for (Class<?> clazz : allTypes) {
            TypeDef<?> typeDef = typeDefMap.get(clazz);
            List<Field_I<?, ?>> typeFields = fieldsMap.get(clazz);

            if (typeDef != null) {
                initDocType(db, typeDef, typeFields);
            } else {
                // Register embedded type without indexes (defaults to DOCUMENT)
                initDocType(db, clazz, ArcadeType.DOCUMENT, typeFields);
            }
        }
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
        var d = InitDoc.initDocType(db, typeDef.definition(), typeDef.arcadeType(), fields);
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
        return this;
    }

    /**
     * Ensure a field exists in the schema using Field_I metadata.
     * ZERO REFLECTION - all type info comes from static field objects.
     *
     * @param field the Field_I object with metadata
     * @return the created or existing Property
     */
    public Property ensureFieldFromMetadata(Field_I<?, ?> field) {
        String fieldName = field.name();

        if (documentType.existsProperty(fieldName)) {
            return documentType.getProperty(fieldName);
        }

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
     * @param indexType the type of index to create
     * @param unique whether the index should be unique
     * @param propertyNames field names for the index
     */
    private void createIndexIfNotAlreadyThere(Schema.INDEX_TYPE indexType, boolean unique, String... propertyNames) {
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
