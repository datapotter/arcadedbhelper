package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.index.Index;
import com.arcadedb.schema.Schema;

import java.util.List;

/**
 * The shape of an index, captured so it can be built again after something takes it away.
 *
 * <p>Everything here is readable from {@link Index} itself, which matters: a capture taken from the
 * live index needs no {@code TypeDef} and no declared schema, so it works for a type this code did
 * not create and for one whose declaration is not to hand.
 *
 * <p>Deliberately NOT captured: the page size. {@link Schema#createTypeIndex} has no overload that
 * takes one, so a rebuild cannot restore it — an index created with a tuned page size reverts to the
 * default. Upstream knows (ArcadeData/arcadedb#5723, closed). Recorded here so the gap is a known
 * limitation rather than a surprise.
 *
 * @param properties the property names the index covers, in order
 * @param kind       LSM_TREE, FULL_TEXT and so on
 * @param unique     whether the index enforces uniqueness — the half that is a CONSTRAINT rather
 *                   than an optimisation, and therefore the half whose loss corrupts data
 */
public record IndexDef(List<String> properties, Schema.INDEX_TYPE kind, boolean unique) {

    public static IndexDef of(Index index) {
        return new IndexDef(List.copyOf(index.getPropertyNames()), index.getType(), index.isUnique());
    }

    /** Build this index on {@code typeName}. Costs a full scan of the type, like any index build. */
    public Index createOn(Database db, String typeName) {
        return db.getSchema().createTypeIndex(kind, unique, typeName, properties.toArray(String[]::new));
    }

    @Override
    public String toString() {
        return typeless() + (unique ? " UNIQUE" : "");
    }

    private String typeless() {
        return kind + String.valueOf(properties);
    }
}
