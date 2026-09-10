package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.MutableDocument;
import datapotter.datahelper.DataHelper_I;
import datapotter.datahelper.Field_I;
import datapotter.datahelper.HasUuid;

/**
 * Typed, field-level upsert for any {@code @ArcadeData} type — DOCUMENT or VERTEX.
 *
 * <p>The instance DSL ({@code obj.in(db).whereEq(..).upsert()}) writes the whole object, which is
 * right when the object <i>is</i> the truth. It is wrong — quietly and dangerously — when you hold a
 * stale snapshot and mean to change one field: every non-null field of the snapshot is pushed back over
 * the row, reverting whatever else has happened since, while null fields survive because the DSL skips
 * them. That makes the corruption partial and easy to miss.
 *
 * <p>This is the complement: name the key, name the fields you mean, touch nothing else.
 *
 * <pre>
 * import static datapotter.arcadedbhelper.Upsert.upsert;
 * import static com.example.model.DriveCorpus_A.*;      // $driveId, $scannedBy
 *
 * upsert(db, DriveCorpus.TYPEDEF)
 *     .key($driveId, driveId)
 *     .set($scannedBy, email)
 *     .save();
 * </pre>
 *
 * <p>Field and value are bound together by the generic signature, so putting a {@code String} into a
 * {@code Long} field does not compile. The type name comes from the {@link TypeDef}, so no string
 * literal appears at the call site, and creation goes through {@link NewRecord}, so a VERTEX type works
 * exactly like a DOCUMENT one.
 *
 * <p>Must be called inside a transaction.
 */
public final class Upsert<E extends DataHelper_I<E>> {

    private final Database db;
    private final String typeName;
    private String keyField;
    private Object keyValue;
    private final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();

    private Upsert(Database db, TypeDef<E> type) {
        this.db = db;
        this.typeName = type.definition().getSimpleName();
    }

    /** Start an upsert against the type declared by {@code type}. */
    public static <E extends DataHelper_I<E>> Upsert<E> upsert(Database db, TypeDef<E> type) {
        return new Upsert<>(db, type);
    }

    /** The field identifying the record. Required. */
    public <T> Upsert<E> key(Field_I<E, T> field, T value) {
        this.keyField = field.name();
        this.keyValue = value;
        return this;
    }

    /** Set a field. Null is written, because "set it to nothing" is a legitimate instruction. */
    public <T> Upsert<E> set(Field_I<E, T> field, T value) {
        values.put(field.name(), value);
        return this;
    }

    /**
     * Set a field only when {@code value} is non-null, leaving any existing value intact.
     *
     * <p>For data that arrives from a source which sometimes omits it — a narrower API field mask, say —
     * so a cheap refresh never erases what a fuller one collected.
     */
    public <T> Upsert<E> setIfPresent(Field_I<E, T> field, T value) {
        if (value != null) values.put(field.name(), value);
        return this;
    }

    /** Finds the record by key and applies the fields, or creates it. */
    public MutableDocument save() {
        if (keyField == null) {
            throw new IllegalStateException("upsert(" + typeName + ") needs a .key(field, value)");
        }
        MutableDocument doc = find();
        if (doc == null) {
            doc = NewRecord.of(db, typeName);
            doc.set(keyField, HasUuid.storageValue(keyValue));
        }
        // Writes straight to the engine (unlike Document_Update.set), so it needs its own enum
        // conversion: an @AsUuid/@AsName constant becomes its stored string here too.
        MutableDocument target = doc;
        values.forEach((field, value) -> target.set(field, HasUuid.storageValue(value)));
        doc.save();
        return doc;
    }

    private MutableDocument find() {
        if (keyValue == null) return null;
        var it = db.select().fromType(typeName)
                .where().property(keyField).eq().value(keyValue)
                .documents();
        return it.hasNext() ? it.next().modify() : null;
    }
}
