package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.Result;
import xyz.jphil.datahelper.DataHelper_I;
import xyz.jphil.datahelper.MapReads;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Trait interface providing ArcadeDB serialization and deserialization capabilities
 * for DataHelper_I implementations.
 *
 * <p>This trait provides two ways to save documents:</p>
 *
 * <h3>1. Traditional Pattern (Still Supported)</h3>
 * <pre>
 * Update.use(db)
 *     .select("PersonDTO")
 *     .whereEq("email", person.getEmail())
 *     .upsert()
 *     .mapValuesWith(person)
 *     .saveDocument();
 * </pre>
 *
 * <h3>2. Instance-Level DSL (New, More Natural)</h3>
 * <pre>
 * // Full DSL - upsert/insert are terminal operations
 * Document doc = person.in(db)
 *     .whereEq("email", person.email())
 *     .from("name", "age")  // selective fields, omit for all
 *     .upsert();            // terminal operation, returns Document
 *
 * // Insert only
 * Document doc = person.in(db)
 *     .insert();
 * </pre>
 *
 * <p>Example usage:
 * <pre>
 * &#64;DataHelper
 * &#64;Getter
 * &#64;Setter
 * public class PersonDTO implements PersonDTO_I&lt;PersonDTO&gt;, ArcadeDoc_I&lt;PersonDTO&gt; {
 *     String name;
 *     String email;
 * }
 *
 * // Load from Document
 * PersonDTO loaded = new PersonDTO();
 * loaded.fromArcadeDocument(doc);
 * </pre>
 */
public interface ArcadeDoc_I<E extends ArcadeDoc_I<E>>
        extends DataHelper_I<E> {

    // ========== Instance-Level DSL Methods ==========

    /**
     * Start a fluent update/insert operation for this document instance.
     * This is the main entry point for the instance-level DSL.
     *
     * <p>Usage:
     * <pre>
     * Document doc = person.in(db)
     *     .whereEq("email", person.email())
     *     .from("name", "age")  // optional, omit for all fields
     *     .upsert();             // terminal operation
     * </pre>
     *
     * @param db the ArcadeDB database
     * @return a fluent builder for document operations
     */
    @SuppressWarnings("unchecked")
    default ArcadeDocUpdate<E> in(Database db) {
        return ArcadeDocUpdate.from(db, (E) this);
    }

    // ========== Deserialization ==========
    // The generic map-to-object walk lives in `base` (MapReads); what is ArcadeDB's own — record
    // identity, edge endpoints, native record types and references — is supplied here and in
    // ArcadeMapReads. An embedded @Data block is therefore reachable: it needs no trait of its own.

    /**
     * Populate this object from an ArcadeDB {@link Document}, recursing through embedded documents,
     * lists and maps of them, and resolving references.
     *
     * <p>Fields are read straight off the document rather than through an intermediate map, so a
     * typed read costs no copy of the record it is reading.</p>
     *
     * @param doc the document to read from; {@code null} is a no-op
     * @return this object for fluent chaining
     */
    @SuppressWarnings("unchecked")
    default E fromArcadeDocument(Document doc) {
        if (doc == null) {
            return (E) this;
        }

        // Capture the record's own identity (read-only; never synced back).
        final var __id = doc.getIdentity();
        if (__id != null) {
            $rid(__id.toString());
        }

        // For edges, capture the @out/@in endpoint RIDs (no-op storage unless this is an EDGE _A).
        if (doc instanceof Edge edge) {
            if (edge.getOut() != null) $out(edge.getOut().toString());
            if (edge.getIn() != null) $in(edge.getIn().toString());
        }

        for (var fieldName : fieldNames()) {
            if (doc.has(fieldName)) {
                MapReads.readField(this, fieldName, doc.get(fieldName), ArcadeMapReads.CONTEXT);
            }
        }

        return (E) this;
    }

    /**
     * Populate this object from a plain map, capturing {@code @rid} if the map carries it (a
     * projected sub-object does). Otherwise identical to {@link #fromArcadeDocument}.
     *
     * @param map the map to read from; {@code null} is a no-op
     * @return this object for fluent chaining
     */
    @SuppressWarnings("unchecked")
    default E fromArcadeMap(Map<String, Object> map) {
        if (map == null) {
            return (E) this;
        }

        final var __rid = map.get("@rid");
        if (__rid != null) {
            $rid(__rid.toString());
        }

        return MapReads.read((E) this, map, ArcadeMapReads.CONTEXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden so a map read through the generic entry point still uses ArcadeDB's context:
     * without this, references would be read as plain values and lost.</p>
     */
    @Override
    default E fromMap(Map<String, Object> map) {
        return fromArcadeMap(map);
    }

    /**
     * Populate this object from a query {@link Result} (a row of a SQL/native query). Resolves the
     * <b>query-shaped</b> path: a nested projection ({@code SELECT *, customer:{*}}) comes back as a
     * sub-{@code Result}, which is deep-mapped so the matching {@link Link}/{@link LinkList} field is
     * returned already resolved. Captures {@code @rid} into {@link #$rid()}.
     */
    default E fromResult(Result r) {
        if (r == null) {
            @SuppressWarnings("unchecked") E self = (E) this;
            return self;
        }
        r.getIdentity().ifPresent(rid -> $rid(rid.toString()));
        return fromArcadeMap(ArcadeReads.toDeepMap(r));
    }

    // ========== Identity (@rid) ==========

    /**
     * The record's own identity (RID), e.g. {@code "#12:3"}; {@code null} if this snapshot was not
     * loaded from the database. Read-only and DB-facing only — set by the ArcadeDB layer on load,
     * never synced back. Default returns {@code null}; generated {@code _A} classes override with
     * real storage. Deliberately excluded from the {@code _R} record projection (it is identity
     * metadata, not value data).
     */
    default String $rid() { return null; }

    /**
     * Internal: bind the record identity. Called only by the ArcadeDB (de)serialization layer; the
     * {@code $}-name marks it as machinery, not application API. Default no-op; generated {@code _A}
     * classes override with real storage.
     */
    default void $rid(String rid) { /* overridden by generated _A */ }

    // ========== Edge endpoints (@out / @in) — overridden by generated EDGE _A ==========

    /**
     * The edge's source endpoint ({@code @out}) as a {@link Link}, or {@code null} for non-edges /
     * unloaded snapshots. Untyped ({@code Link<?>}): the endpoint's RID is always present; an optional
     * typed endpoint declaration is deferred, so {@link Link#resolve} needs the target type supplied.
     */
    default Link<?> $out() { return null; }

    /** The edge's target endpoint ({@code @in}) as a {@link Link}; see {@link #$out()}. */
    default Link<?> $in() { return null; }

    /** Internal: bind the edge {@code @out} RID. Called only by the load layer. */
    default void $out(String rid) { /* overridden by generated EDGE _A */ }

    /** Internal: bind the edge {@code @in} RID. Called only by the load layer. */
    default void $in(String rid) { /* overridden by generated EDGE _A */ }

    // ========== Vertex adjacency — traversal as explicit resolution (takes db, no hidden I/O) ==========

    /**
     * Out-adjacent vertices reachable over the given edge types, resolved into snapshots built by
     * {@code factory} ({@code Neighbour::new}). Like {@link Link#resolve(Database)} this is the
     * explicit I/O point — a vertex snapshot does not "have" its neighbours until you ask {@code db}
     * for them. Edge types are the generated type classes (e.g. {@code Knows.class}); none means all.
     */
    default <X extends ArcadeDoc_I<X>> LinkList<X> $out(Database db, Supplier<X> factory, Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(db, $rid(), Vertex.DIRECTION.OUT, factory, edgeTypes);
    }

    /** In-adjacent vertices; see {@link #$out(Database, Supplier, Class...)}. */
    default <X extends ArcadeDoc_I<X>> LinkList<X> $in(Database db, Supplier<X> factory, Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(db, $rid(), Vertex.DIRECTION.IN, factory, edgeTypes);
    }

    /** Both-direction adjacent vertices; see {@link #$out(Database, Supplier, Class...)}. */
    default <X extends ArcadeDoc_I<X>> LinkList<X> $both(Database db, Supplier<X> factory, Class<?>... edgeTypes) {
        return ArcadeReads.adjacency(db, $rid(), Vertex.DIRECTION.BOTH, factory, edgeTypes);
    }

    // ========== Reference (LINK) metadata — overridden by generated _A ==========

    /** True if the property is a single reference ({@code Link<T>}). */
    default boolean isLinkField(String propertyName) { return false; }

    /** True if the property is a list of references ({@code LinkList<T>}). */
    default boolean isLinkListField(String propertyName) { return false; }

    /** True if the property is a keyed map of references ({@code LinkMap<K,T>}). */
    default boolean isLinkMapField(String propertyName) { return false; }

    /** The target (linked) entity type of a reference property, or {@code null}. */
    default Class<?> linkTargetType(String propertyName) { return null; }

    /** The key type of a {@code LinkMap} property, or {@code null}. */
    default Class<?> linkKeyType(String propertyName) { return null; }

    /** Create a fresh target instance for a reference property (to populate a projection), or {@code null}. */
    default ArcadeDoc_I<?> createLinkTarget(String propertyName) { return null; }

    /**
     * Build a {@link Link} from a raw property value for a reference field: a bare RID (unresolved)
     * or a projected sub-document / map (resolved). Never performs I/O.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    default Link<?> toLink(String fieldName, Object value) {
        if (value == null) return null;
        // A projected sub-object arrives as a query Result (SELECT *, customer:{...}); normalise it to
        // a map so the Map branch below resolves it. This is the query-shaped resolution path.
        if (value instanceof Result r) return toLink(fieldName, ArcadeReads.toDeepMap(r));
        // Zero-reflection target factory: createLinkTarget(fieldName) is the generated `new Target()`.
        final Supplier factory = () -> createLinkTarget(fieldName);
        if (value instanceof Document) {
            final Document d = (Document) value;
            final String rid = d.getIdentity() != null ? d.getIdentity().toString() : null;
            final ArcadeDoc_I<?> target = createLinkTarget(fieldName);
            if (target != null) {
                ((ArcadeDoc_I) target).fromArcadeDocument(d);
                return Link.resolved(rid, (ArcadeDoc_I) target);
            }
            return Link.ofRid(rid, factory);
        }
        if (value instanceof Identifiable) {
            final Identifiable id = (Identifiable) value;
            final String rid = id.getIdentity() != null ? id.getIdentity().toString() : value.toString();
            return Link.ofRid(rid, factory);
        }
        if (value instanceof Map) {
            final Map<String, Object> m = (Map<String, Object>) value;
            final Object ridVal = m.get("@rid");
            final String rid = ridVal != null ? ridVal.toString() : null;
            final ArcadeDoc_I<?> target = createLinkTarget(fieldName);
            if (target != null) {
                ((ArcadeDoc_I) target).fromArcadeMap(m);
                return Link.resolved(rid, (ArcadeDoc_I) target);
            }
            return Link.ofRid(rid, factory);
        }
        return Link.ofRid(value.toString(), factory);
    }
}
