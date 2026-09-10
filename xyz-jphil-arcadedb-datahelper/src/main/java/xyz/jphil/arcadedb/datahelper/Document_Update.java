package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.database.EmbeddedDocument;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.MutableEmbeddedDocument;
import com.arcadedb.database.RID;
import com.arcadedb.schema.Type;
import xyz.jphil.datahelper.DataHelper_I;
import xyz.jphil.datahelper.Field_I;
import xyz.jphil.datahelper.HasUuid;
import xyz.jphil.datahelper.MapWrites;

import java.util.*;
import java.util.function.Supplier;

/**
 * Fluent API for updating ArcadeDB documents.
 */
public class Document_Update {

    private final MutableDocument mdoc;
    private boolean updateNullValues = false;
    private boolean doNotUpdate;
    private ErrorHandler errorHandler;

    public static final ErrorHandler LOG_TO_STD_OUT = new ErrorHandler() {
        @Override
        public void errorInUpdating(String fieldName, Exception ex) {
            System.out.println("Failed to update " + fieldName);
            ex.printStackTrace(System.out);
        }

        @Override
        public void skippedUpdate(String fieldName) {
            System.out.println("Skipped updating null field " + fieldName);
        }
    };

    public interface ErrorHandler {
        void errorInUpdating(String fieldName, Exception ex);
        void skippedUpdate(String fieldName);
    }

    /**
     * Enable updating null values (default is to skip null values).
     *
     * @return this Document_Update instance
     */
    public Document_Update updateNullValues() {
        return updateNullValues(true);
    }

    /**
     * Set error handler for update operations.
     *
     * @param errorHandler the error handler
     * @return this Document_Update instance
     */
    public Document_Update errorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    /**
     * Configure whether to update null values.
     *
     * @param updateNullValues true to update null values, false to skip them
     * @return this Document_Update instance
     */
    public Document_Update updateNullValues(boolean updateNullValues) {
        this.updateNullValues = updateNullValues;
        return this;
    }

    public Document_Update(MutableDocument mdoc) {
        this(mdoc, false);
    }

    public Document_Update(MutableDocument mdoc, boolean doNotUpdate) {
        this.mdoc = mdoc;
        this.doNotUpdate = doNotUpdate;
    }

    /**
     * Static factory method to create a Document_Update.
     *
     * @param md the mutable document to update
     * @return new Document_Update instance
     */
    public static Document_Update updateDocument(MutableDocument md) {
        return new Document_Update(md);
    }

    /**
     * Update fields from a FieldNameToValuesMapProvider.
     *
     * @param mapProvider the map provider
     * @return this Document_Update instance
     */
    public Document_Update with(FieldNameToValuesMapProvider mapProvider) {
        for (var e : mapProvider.map().entrySet()) {
            __(e.getKey(), () -> e.getValue());
        }
        return this;
    }

    /**
     * Update fields from a Map.
     *
     * @param map map of field names to values
     * @param fields optional field names to copy (if empty, copies all fields)
     * @return this Document_Update instance
     */
    public Document_Update from(Map<String, Object> map, String... fields) {
        if (fields == null || fields.length == 0) {
            // Map all fields
            for (var e : map.entrySet()) {
                __(e.getKey(), () -> e.getValue());
            }
        } else {
            // Map only specified fields
            for (String field : fields) {
                if (map.containsKey(field)) {
                    __(field, () -> map.get(field));
                }
            }
        }
        return this;
    }

    /**
     * Update fields directly from a DataHelper object (type-safe).
     *
     * <p>Type-safe version for refactor-safe field selection.
     * Example:
     * <pre>
     * import static TestPersonDTO_I.*;
     *
     * Document_Update.updateDocument(mDoc)
     *     .from(person, $name, $age)  // Only copy these fields
     *     .saveDocument();
     * </pre>
     *
     * @param dataHelper the DataHelper object to read values from
     * @param fields field objects to copy (if empty, copies all fields)
     * @return this Document_Update instance
     */
    @SafeVarargs
    public final Document_Update from(xyz.jphil.datahelper.DataHelper_I<?> dataHelper, Field_I<?, ?>... fields) {
        String[] fieldNames = Arrays.stream(fields)
            .map(Field_I::name)
            .toArray(String[]::new);
        return from(dataHelper, fieldNames);
    }

    /**
     * Update fields directly from a DataHelper object (string-based).
     *
     * <p>String-based version for dynamic field selection.
     * Example:
     * <pre>
     * Document_Update.updateDocument(mDoc)
     *     .from(person, "name", "age")  // Only copy these fields
     *     .saveDocument();
     * </pre>
     *
     * @param dataHelper the DataHelper object to read values from
     * @param fields optional field names to copy (if empty, copies all fields)
     * @return this Document_Update instance
     */
    public Document_Update from(xyz.jphil.datahelper.DataHelper_I<?> dataHelper, String... fields) {
        if (fields == null || fields.length == 0) {
            // Map all fields
            for (String fieldName : dataHelper.fieldNames()) {
                __(fieldName, () -> dataHelper.getPropertyByName(fieldName));
            }
        } else {
            // Map only specified fields
            for (String fieldName : fields) {
                __(fieldName, () -> dataHelper.getPropertyByName(fieldName));
            }
        }
        return this;
    }

    // String-based version with Supplier (for error handling)
    public Document_Update __(String field, Supplier<?> valueProvider) {
        assert valueProvider != null;
        assert field != null;
        try {
            var val = valueProvider.get();
            if (val != null || (val == null && updateNullValues)) {
                if (!doNotUpdate) {
                    set(field, val);
                }
            } else {
                if (errorHandler != null) {
                    errorHandler.skippedUpdate(field);
                }
            }
        } catch (Exception e) {
            if (errorHandler != null) {
                errorHandler.errorInUpdating(field, e);
            }
        }
        return this;
    }

    // String-based version with direct value (convenience)
    public Document_Update __(String field, Object value) {
        return __(field, () -> value);
    }

    // Type-safe Field_I with Supplier
    public <T> Document_Update __(xyz.jphil.datahelper.Field_I<?, T> field, Supplier<T> valueProvider) {
        return __(field.name(), valueProvider);
    }

    // Type-safe Field_I with direct value
    public <T> Document_Update __(xyz.jphil.datahelper.Field_I<?, T> field, T value) {
        return __(field.name(), () -> value);
    }

    /**
     * Set a field value on the document.
     * Handles embedded DataHelper objects, Lists, and Maps by converting them appropriately.
     *
     * @param field the field name
     * @param val the value to set
     */
    private void set(String field, Object val) {
        // Enum field (Phase 1, PRP-28): an @AsUuid constant becomes its uuid(), a plain @AsName
        // constant becomes its name() — see HasUuid.storageValue. A no-op for every other value.
        val = HasUuid.storageValue(val);

        var mySchema = mdoc.getDatabase().getSchema().getType(mdoc.getTypeName());
        var property = mySchema.getProperty(field);

        if (property == null) {
            // Property not defined in schema, set as-is
            mdoc.set(field, val);
            return;
        }

        Type fieldType = property.getType();

        if (fieldType == Type.EMBEDDED) {
            // Handle embedded DataHelper object
            if (val instanceof DataHelper_I) {
                DataHelper_I<?> dataHelper = (DataHelper_I<?>) val;
                String embeddedTypeName = dataHelper.dataClass().getSimpleName();

                // Create embedded document and populate it
                MutableEmbeddedDocument embeddedDoc = mdoc.newEmbeddedDocument(embeddedTypeName, field);
                populateEmbeddedDocument(embeddedDoc, dataHelper);
            } else if (val instanceof Map) {
                // Already a map, set directly
                mdoc.set(field, val);
            } else if (val == null) {
                mdoc.set(field, null);
            } else {
                throw new IllegalArgumentException("Expected DataHelper_I or Map for EMBEDDED field " + field +
                    ", got " + val.getClass().getName());
            }

        } else if (fieldType == Type.LINK) {
            // Reference (LINK): store only the target's RID, never an embedded copy
            if (val instanceof Link<?> link) {
                mdoc.set(field, link.rid() == null ? null : RID.create(mdoc.getDatabase(), link.rid()));
            } else {
                mdoc.set(field, val); // RID / Identifiable / String passthrough
            }

        } else if (fieldType == Type.LIST) {
            // Handle list (links -> list of RIDs; otherwise embedded objects)
            if (val instanceof LinkList<?> linkList) {
                List<Object> rids = new ArrayList<>();
                for (Link<?> l : linkList.links()) {
                    rids.add(l.rid() == null ? null : RID.create(mdoc.getDatabase(), l.rid()));
                }
                mdoc.set(field, rids);
            } else if (val instanceof List) {
                List<?> list = (List<?>) val;
                List<Object> convertedList = new ArrayList<>();

                for (Object item : list) {
                    if (item instanceof DataHelper_I) {
                        // Convert DataHelper to Map
                        convertedList.add(dataHelperToMap((DataHelper_I<?>) item));
                    } else {
                        convertedList.add(item);
                    }
                }

                mdoc.set(field, convertedList);
            } else {
                mdoc.set(field, val);
            }

        } else if (fieldType == Type.MAP) {
            // Handle map (links -> map of RIDs; otherwise embedded objects)
            if (val instanceof LinkMap<?, ?> linkMap) {
                Map<Object, Object> rids = new HashMap<>();
                linkMap.links().forEach((k, v) ->
                    rids.put(k, v.rid() == null ? null : RID.create(mdoc.getDatabase(), v.rid())));
                mdoc.set(field, rids);
            } else if (val instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) val;
                Map<Object, Object> convertedMap = new HashMap<>();

                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof DataHelper_I) {
                        // Convert DataHelper to Map
                        convertedMap.put(entry.getKey(), dataHelperToMap((DataHelper_I<?>) value));
                    } else {
                        convertedMap.put(entry.getKey(), value);
                    }
                }

                mdoc.set(field, convertedMap);
            } else {
                mdoc.set(field, val);
            }

        } else {
            // Regular field, set as-is
            mdoc.set(field, val);
        }
    }

    /**
     * Populate an EmbeddedDocument from a DataHelper_I object.
     *
     * @param embeddedDoc the embedded document to populate
     * @param dataHelper the source DataHelper object
     */
    private void populateEmbeddedDocument(MutableEmbeddedDocument embeddedDoc, DataHelper_I<?> dataHelper) {
        for (String fieldName : dataHelper.fieldNames()) {
            Object value = dataHelper.getPropertyByName(fieldName);
            if (value == null) {
                continue;
            }

            if (dataHelper.isNestedObjectField(fieldName) && value instanceof DataHelper_I) {
                // Nested embedded object
                DataHelper_I<?> nested = (DataHelper_I<?>) value;
                String nestedTypeName = nested.dataClass().getSimpleName();
                MutableEmbeddedDocument nestedDoc = embeddedDoc.newEmbeddedDocument(nestedTypeName, fieldName);
                populateEmbeddedDocument(nestedDoc, nested);

            } else if (dataHelper.isListField(fieldName) && value instanceof List) {
                // List field
                List<?> list = (List<?>) value;
                List<Object> convertedList = new ArrayList<>();

                for (Object item : list) {
                    if (item instanceof DataHelper_I) {
                        convertedList.add(dataHelperToMap((DataHelper_I<?>) item));
                    } else {
                        convertedList.add(HasUuid.storageValue(item));
                    }
                }

                embeddedDoc.set(fieldName, convertedList);

            } else if (dataHelper.isMapField(fieldName) && value instanceof Map) {
                // Map field
                Map<?, ?> map = (Map<?, ?>) value;
                Map<Object, Object> convertedMap = new HashMap<>();

                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object mapValue = entry.getValue();
                    if (mapValue instanceof DataHelper_I) {
                        convertedMap.put(entry.getKey(), dataHelperToMap((DataHelper_I<?>) mapValue));
                    } else {
                        convertedMap.put(entry.getKey(), HasUuid.storageValue(mapValue));
                    }
                }

                embeddedDoc.set(fieldName, convertedMap);

            } else {
                // Regular field (enum-aware; see HasUuid.storageValue)
                embeddedDoc.set(fieldName, HasUuid.storageValue(value));
            }
        }
    }

    /**
     * Convert a DataHelper object to a Map for serialization.
     *
     * <p>The conversion is generic — nested blocks, lists and maps of them, enum values — so it
     * lives in {@code base} as {@link MapWrites}, where a {@code @Data} type can round-trip through
     * a plain map with no database involved. This remains the call site the LIST and MAP branches
     * above use.</p>
     *
     * @param dataHelper the DataHelper object
     * @return a Map representation
     */
    private Map<String, Object> dataHelperToMap(DataHelper_I<?> dataHelper) {
        return MapWrites.toMap(dataHelper);
    }

    /**
     * Save the document and return it.
     *
     * @return the saved MutableDocument
     */
    public MutableDocument saveDocument() {
        if (!doNotUpdate) {
            mdoc.save();
        }
        return mdoc;
    }
}
