package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.VertexType;

/**
 * Creates a new record of a schema type, whatever kind of type it is.
 *
 * <p>In ArcadeDB a vertex <i>is</i> a document — {@code VertexType} extends {@code DocumentType} and
 * {@code MutableVertex} extends {@code MutableDocument} — so the only thing that ever needed to differ
 * between creating a document and creating a vertex is which factory method is called. Calling
 * {@code newDocument} on a type declared {@code @ArcadeData(type = VERTEX)} fails with
 * <em>"Cannot create a document of type 'X' because is not a document type"</em>, which made the whole
 * instance DSL ({@code obj.in(db).whereEq(...).upsert()}) unusable for vertices even though everything
 * downstream of the creation works identically.
 *
 * <p>This dispatches on the declared schema type instead, so the typed DSL now covers DOCUMENT and
 * VERTEX alike.
 *
 * <p>Edges are deliberately refused: an edge cannot exist without its two endpoints, so there is
 * nothing sensible for a keyed upsert to create. Build those with
 * {@code vertex.newEdge(type, target)}.
 */
public final class NewRecord {

    private NewRecord() {
    }

    public static MutableDocument of(Database db, String typeName) {
        var type = db.getSchema().getType(typeName);

        if (type instanceof EdgeType) {
            throw new IllegalArgumentException(
                    "'" + typeName + "' is an EDGE type: an edge needs both endpoints, so it cannot be "
                    + "created by a keyed upsert. Use sourceVertex.newEdge(\"" + typeName + "\", target).");
        }
        if (type instanceof VertexType) {
            return db.newVertex(typeName);   // MutableVertex IS a MutableDocument
        }
        return db.newDocument(typeName);
    }
}
