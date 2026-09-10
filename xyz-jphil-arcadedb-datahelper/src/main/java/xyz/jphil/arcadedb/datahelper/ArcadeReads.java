package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.Result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Read-side helpers for the ArcadeDB trait, kept out of {@link ArcadeDoc_I} to keep that interface
 * lean: normalising a query {@link Result} into a plain map (so the existing map deserialisation
 * resolves nested {@code :{}} projections), and graph adjacency traversal for vertex snapshots.
 */
final class ArcadeReads {
    private ArcadeReads() {}

    /**
     * Deep-convert a query {@link Result} (element or projection) into a plain map, turning nested
     * projection sub-results into nested maps and collections of sub-results into lists of maps.
     * {@code @rid} is captured from the identity; other {@code @}-metadata is dropped. The result
     * flows through {@link ArcadeDoc_I#fromArcadeMap} unchanged — which is how a {@code SELECT *,
     * customer:&#123;*&#125;} projection comes back as a <em>resolved</em> {@link Link}.
     */
    static Map<String, Object> toDeepMap(Result r) {
        var m = new LinkedHashMap<String, Object>();
        r.getIdentity().ifPresent(rid -> m.put("@rid", rid.toString()));
        for (var name : r.getPropertyNames()) {
            if (name.startsWith("@")) continue;          // metadata; @rid already captured above
            m.put(name, normalize(r.getProperty(name)));
        }
        return m;
    }

    private static Object normalize(Object v) {
        return switch (v) {
            case null -> null;
            case Result nested -> toDeepMap(nested);
            case Collection<?> c -> c.stream().map(ArcadeReads::normalize).toList();
            default -> v;
        };
    }

    /**
     * Graph adjacency as explicit resolution: load the vertex by {@code rid}, traverse
     * {@code direction} over the given edge types, and resolve each neighbour into a fresh {@code X}
     * snapshot built by {@code factory} ({@code Neighbour::new} — zero reflection). The single I/O
     * entry point for vertex traversal — mirrors {@link Link#resolve(Database)}; never from a getter.
     * Edge types are passed as generated type classes (e.g. {@code Knows.class}); the engine label
     * is their simple name. No {@code edgeTypes} means "all edge types".
     */
    static <X extends ArcadeDoc_I<X>> LinkList<X> adjacency(Database db, String rid,
            Vertex.DIRECTION direction, Supplier<X> factory, Class<?>... edgeTypes) {
        if (rid == null) return LinkList.of(List.of());
        return adjacency(RID.create(db, rid).asVertex(true), direction, factory, edgeTypes);
    }

    /**
     * The same traversal, starting from a {@link Vertex} the caller already holds.
     *
     * <p>The {@code rid} overload above has to spend a {@code RID.create(db, rid).asVertex(true)} to
     * get back a vertex — which is pure waste for a caller that reached the vertex through an indexed
     * lookup and never needed a snapshot of it. Walking a subtree that way costs one redundant record
     * fetch per node. Hence this entry point: same semantics, no round-trip.
     */
    static <X extends ArcadeDoc_I<X>> LinkList<X> adjacency(Vertex v, Vertex.DIRECTION direction,
            Supplier<X> factory, Class<?>... edgeTypes) {
        if (v == null) return LinkList.of(List.of());
        var labels = Arrays.stream(edgeTypes).map(Class::getSimpleName).toArray(String[]::new);
        var links = new ArrayList<Link<X>>();
        for (var n : v.getVertices(direction, labels)) {
            var x = factory.get();
            x.fromArcadeDocument(n);                       // a Vertex IS a Document
            links.add(Link.of(x));
        }
        return LinkList.of(links);
    }
}
