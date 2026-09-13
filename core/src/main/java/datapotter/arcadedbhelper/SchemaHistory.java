package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * The schema's own history (PRP-28 phase 3): per init run, what every identified type and property
 * was called and typed at that moment.
 *
 * <p>Its strongest use is one a live database never exercises: a backup restored into newer code.
 * The history answers what the schema looked like when the archive was taken — the same argument
 * that settled the vocabulary uuids in phase 1, one level up. "This property was {@code outcome}
 * until September, then {@code outcomeCode}" is a different quality of information from "there is
 * an unmatched column," and it is what lets a human or an AI DECIDE a migration instead of guessing
 * it from a two-point diff.
 *
 * <p><b>The type name deliberately carries a hyphen</b>, a character no Java identifier can produce
 * — not a convention to remember, a structural impossibility, so no {@code @ArcadeData} class can
 * ever collide with it.
 *
 * <p><b>This becomes part of the schema of record.</b> It is data — backed up with everything else,
 * never hand-edited — and it is not pruned: schema changes are rare, so unbounded growth is not a
 * real concern here, and the value is precisely in the old entries.
 */
public final class SchemaHistory {

    /** Illegal as a Java identifier on purpose — see the class Javadoc. */
    public static final String TYPE_NAME = "datapotter-schema-log";

    private SchemaHistory() {}

    /**
     * Record one row per identified type and per identified property, all sharing one freshly
     * minted {@code runId}.
     *
     * <p>Call this AFTER {@link InitDoc#initDocTypes}, not instead of it, so what is recorded is the
     * schema the run actually left behind — ids, current names and current storage types, whether
     * this run created them, adopted them, renamed a type onto them, or left them untouched.
     *
     * @return the runId this call recorded under, for a caller that wants to name it in its own log
     */
    public static String recordRun(Database db) {
        Schema schema = db.getSchema();
        ensureType(schema);

        String runId = Instant.now() + "-" + UUID.randomUUID().toString().substring(0, 8);
        db.transaction(() -> {
            for (DocumentType type : schema.getTypes()) {
                if (type.getName().equals(TYPE_NAME)) continue; // never log itself
                Object typeId = type.getCustomValue(InitDoc.ID_KEY);
                if (typeId != null) {
                    row(db, runId, "TYPE", type.getName(), null, String.valueOf(typeId), null);
                }
                for (Property p : type.getProperties()) {
                    Object propId = p.getCustomValue(InitDoc.ID_KEY);
                    if (propId == null) continue; // unidentified — nothing to compare across a run
                    row(db, runId, "PROPERTY", type.getName(), p.getName(),
                            String.valueOf(propId), p.getType().name());
                }
            }
        });
        return runId;
    }

    private static void row(Database db, String runId, String kind, String typeName,
                             String propertyName, String id, String storedType) {
        db.newDocument(TYPE_NAME)
                .set("runId", runId)
                .set("recordedAt", Instant.now().toString())
                .set("elementKind", kind)
                .set("typeName", typeName)
                .set("propertyName", propertyName)
                .set("id", id)
                .set("storedType", storedType)
                .save();
    }

    private static void ensureType(Schema schema) {
        if (schema.existsType(TYPE_NAME)) return;
        DocumentType t = schema.createDocumentType(TYPE_NAME);
        t.createProperty("runId", Type.STRING);
        t.createProperty("recordedAt", Type.STRING);
        t.createProperty("elementKind", Type.STRING);
        t.createProperty("typeName", Type.STRING);
        t.createProperty("propertyName", Type.STRING);
        t.createProperty("id", Type.STRING);
        t.createProperty("storedType", Type.STRING);
        schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, TYPE_NAME, new String[]{"runId"});
    }
}
