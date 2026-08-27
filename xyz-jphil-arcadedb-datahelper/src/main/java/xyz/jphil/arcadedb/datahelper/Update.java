package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.index.IndexCursor;
import xyz.jphil.datahelper.HasUuid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Fluent API for upserting documents in ArcadeDB.
 */
public class Update {

    private final Database database;
    private String typeName;
    private final HashMap<String, Object> lookupBy = new HashMap<>();

    public Update(Database database) {
        this.database = database;
    }

    public static Update use(Database database) {
        return new Update(database);
    }

    public Update select(String typeName) {
        this.typeName = typeName;
        return this;
    }

    // String-based version
    public Update whereEq(String key, Object value) {
        // Enum-aware (Phase 1, PRP-28): a raw enum value would otherwise reach the index lookup
        // below, which is keyed on the stored uuid/name string, not the Java constant.
        lookupBy.put(key, HasUuid.storageValue(value));
        return this;
    }

    // Type-safe Field_I version
    public <T> Update whereEq(xyz.jphil.datahelper.Field_I<?, T> field, T value) {
        return whereEq(field.name(), value);
    }

    public Document_Update upsert() {
        return upsert(null);
    }

    public Document_Update upsert(Consumer<Boolean> alreadyExists) {
        var mDoc = impl(alreadyExists);
        return Document_Update.updateDocument(mDoc);
    }

    public Document_Update updateNewOnly(Consumer<Boolean> alreadyExists) {
        var mDoc = impl(alreadyExists);
        return new Document_Update(mDoc, true);
    }

    public MutableDocument impl(Consumer<Boolean> alreadyExists) {
        var keys = new ArrayList<String>();
        var vals = new ArrayList<Object>();

        for (var e : lookupBy.entrySet()) {
            keys.add(e.getKey());
            vals.add(e.getValue());
        }

        IndexCursor cur;
        var sz = lookupBy.size();
        assert sz > 0;

        if (sz == 1) {
            var x = lookupBy.entrySet().iterator().next();
            cur = database.lookupByKey(typeName, x.getKey(), x.getValue());
        } else {
            cur = database.lookupByKey(typeName,
                keys.toArray(__ -> new String[__]),
                vals.toArray(__ -> new Object[__]));
        }

        var alreadyExistsUpdater = (Consumer<Boolean>) (t) -> {
            try {
                if (alreadyExists != null) {
                    alreadyExists.accept(t);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        MutableDocument mDoc;
        if (cur == null || !cur.hasNext()) {
            alreadyExistsUpdater.accept(false);
            mDoc = NewRecord.of(database, typeName);   // VERTEX-aware: a vertex IS a document
            return mDoc;
        } else {
            alreadyExistsUpdater.accept(true);
            mDoc = cur.next().getRecord().asDocument().modify();
            if (cur.hasNext()) {
                throw new IllegalStateException("Multiple results which one to upsert?");
            }
            return mDoc;
        }
    }
}
