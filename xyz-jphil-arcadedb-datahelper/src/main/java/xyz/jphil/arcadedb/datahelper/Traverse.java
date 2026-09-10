package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.graph.Vertex;

import java.util.function.Supplier;

/**
 * Graph adjacency for a caller that already holds the {@link Vertex}.
 *
 * <p>{@code ArcadeDoc_I}'s {@code $out}/{@code $in}/{@code $both} are the natural form when you hold a
 * <em>snapshot</em>: they read its {@code $rid()} and fetch the vertex behind it. That is the wrong
 * shape when the vertex is what you already have — a lookup by business key returns a {@code Vertex},
 * and going through the snapshot would mean materialising a full DTO purely to read its RID, then
 * spending a second fetch to arrive back at the vertex you started from. Over a subtree walk that is a
 * redundant record fetch per node.
 *
 * <p>So this is the same traversal addressed the other way round. Identical semantics to
 * {@code $out}/{@code $in}/{@code $both} — edge types are the generated type classes
 * ({@code ParentOf.class}), none means all edge types, and neighbours come back as resolved snapshots
 * built by a zero-reflection {@code Neighbour::new} factory.
 *
 * <pre>
 * Vertex folder = db.select().fromType(DriveFile.__)
 *         .where().property($fileId.__).eq().value(id).vertices().nextOrNull();
 *
 * List&lt;DriveFile&gt; children = Traverse.out(folder, DriveFile::new, ParentOf.class).getAll();
 * </pre>
 */
public final class Traverse {

    private Traverse() {
    }

    /** Out-adjacent vertices over the given edge types, as resolved snapshots. */
    public static <X extends ArcadeDoc_I<X>> LinkList<X> out(Vertex v, Supplier<X> factory,
            Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(v, Vertex.DIRECTION.OUT, factory, edgeTypes);
    }

    /** In-adjacent vertices; see {@link #out}. */
    public static <X extends ArcadeDoc_I<X>> LinkList<X> in(Vertex v, Supplier<X> factory,
            Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(v, Vertex.DIRECTION.IN, factory, edgeTypes);
    }

    /** Both-direction adjacent vertices; see {@link #out}. */
    public static <X extends ArcadeDoc_I<X>> LinkList<X> both(Vertex v, Supplier<X> factory,
            Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(v, Vertex.DIRECTION.BOTH, factory, edgeTypes);
    }

    /** Adjacency in an explicitly given direction, for code that carries the direction as data. */
    public static <X extends ArcadeDoc_I<X>> LinkList<X> adjacent(Vertex v, Vertex.DIRECTION direction,
            Supplier<X> factory, Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(v, direction, factory, edgeTypes);
    }
}
