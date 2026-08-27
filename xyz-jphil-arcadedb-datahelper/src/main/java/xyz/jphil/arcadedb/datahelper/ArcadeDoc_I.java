package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.sql.executor.Result;
import xyz.jphil.datahelper.DataHelper_I;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ========== Deserialization Methods ==========

    /**
     * Populate this object from an ArcadeDB Document.
     * Handles nested objects, lists, and maps recursively.
     *
     * @param doc the ArcadeDB document to read from
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

        for (String fieldName : fieldNames()) {
            if (!doc.has(fieldName)) {
                continue;
            }

            Object value = doc.get(fieldName);
            if (value == null) {
                continue;
            }

            Class<?> fieldType = getPropertyType(fieldName);
            if (fieldType == null) {
                continue;
            }

            if (isLinkField(fieldName)) {
                // Reference (LINK): value is a bare RID (unresolved) or a projected sub-document
                setPropertyByName(fieldName, toLink(fieldName, value));
            } else if (isLinkListField(fieldName) && value instanceof List) {
                // List of references (LIST of LINK)
                List<Link<?>> ls = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    ls.add(toLink(fieldName, item));
                }
                setPropertyByName(fieldName, new LinkList(ls));
            } else if (isLinkMapField(fieldName) && value instanceof Map) {
                // Keyed map of references (MAP of LINK)
                Class<?> keyType = linkKeyType(fieldName);
                Map<Object, Link<?>> lm = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    lm.put(DataHelper_I.convertType(e.getKey(), keyType), toLink(fieldName, e.getValue()));
                }
                setPropertyByName(fieldName, new LinkMap(lm));
            } else if (isEnumField(fieldName) && value instanceof String storedValue) {
                // Enum field (Phase 1, PRP-28): stored as a uuid or name string; resolve to the
                // declared constant. Never throws — an unmatched id means the row is newer than
                // this build, not that it is corrupt; resolveEnumFromStorage returns null for it.
                setPropertyByName(fieldName, resolveEnumFromStorage(fieldName, storedValue));
            } else if (isNestedObjectField(fieldName)) {
                // Nested DataHelper object
                if (value instanceof Document) {
                    // ArcadeDB Document (ImmutableEmbeddedDocument)
                    DataHelper_I<?> nested = createNestedObject(fieldName);
                    if (nested instanceof ArcadeDoc_I) {
                        ((ArcadeDoc_I<?>) nested).fromArcadeDocument((Document) value);
                        setPropertyByName(fieldName, nested);
                    }
                } else if (value instanceof Map) {
                    // Plain Map
                    DataHelper_I<?> nested = createNestedObject(fieldName);
                    if (nested instanceof ArcadeDoc_I) {
                        ((ArcadeDoc_I<?>) nested).fromArcadeMap((Map<String, Object>) value);
                        setPropertyByName(fieldName, nested);
                    }
                }
            } else if (isListField(fieldName) && value instanceof List) {
                // List field
                List<?> sourceList = (List<?>) value;
                List<Object> targetList = new ArrayList<>();

                for (Object item : sourceList) {
                    if (item instanceof Document) {
                        // ArcadeDB Document (ImmutableEmbeddedDocument)
                        DataHelper_I<?> listElement = createListElement(fieldName);
                        if (listElement instanceof ArcadeDoc_I) {
                            ((ArcadeDoc_I<?>) listElement).fromArcadeDocument((Document) item);
                            targetList.add(listElement);
                        } else {
                            targetList.add(item);
                        }
                    } else if (item instanceof Map) {
                        // Plain Map
                        DataHelper_I<?> listElement = createListElement(fieldName);
                        if (listElement instanceof ArcadeDoc_I) {
                            ((ArcadeDoc_I<?>) listElement).fromArcadeMap((Map<String, Object>) item);
                            targetList.add(listElement);
                        } else {
                            targetList.add(item);
                        }
                    } else {
                        targetList.add(item);
                    }
                }
                setPropertyByName(fieldName, targetList);
            } else if (isMapField(fieldName) && value instanceof Map) {
                // Map field
                Map<?, ?> sourceMap = (Map<?, ?>) value;
                Map<Object, Object> targetMap = (Map<Object, Object>) createMapInstance(fieldName);

                Class<?> keyType = getMapKeyType(fieldName);
                Class<?> valueType = getMapValueType(fieldName);

                for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                    Object key = DataHelper_I.convertType(entry.getKey(), keyType);
                    Object mapValue = entry.getValue();

                    if (isMapValueDataHelper(fieldName) && mapValue instanceof Map) {
                        DataHelper_I<?> mapValueElement = createMapValueElement(fieldName);
                        if (mapValueElement instanceof ArcadeDoc_I) {
                            ((ArcadeDoc_I<?>) mapValueElement).fromArcadeMap((Map<String, Object>) mapValue);
                            targetMap.put(key, mapValueElement);
                        } else {
                            targetMap.put(key, mapValue);
                        }
                    } else {
                        Object convertedValue = DataHelper_I.convertType(mapValue, valueType);
                        targetMap.put(key, convertedValue);
                    }
                }
                setPropertyByName(fieldName, targetMap);
            } else {
                // Simple field - convert and set
                Object convertedValue = DataHelper_I.convertType(value, fieldType);
                setPropertyByName(fieldName, convertedValue);
            }
        }

        return (E) this;
    }

    /**
     * Populate this object from a Map.
     * Similar to fromArcadeDocument but works with plain Maps.
     *
     * @param map the map to read from
     * @return this object for fluent chaining
     */
    @SuppressWarnings("unchecked")
    default E fromArcadeMap(Map<String, Object> map) {
        if (map == null) {
            return (E) this;
        }

        // Capture identity if the map carries it (e.g. a projected sub-object's @rid).
        final Object __rid = map.get("@rid");
        if (__rid != null) {
            $rid(__rid.toString());
        }

        for (String fieldName : fieldNames()) {
            if (!map.containsKey(fieldName)) {
                continue;
            }

            Object value = map.get(fieldName);
            if (value == null) {
                continue;
            }

            Class<?> fieldType = getPropertyType(fieldName);
            if (fieldType == null) {
                continue;
            }

            if (isLinkField(fieldName)) {
                setPropertyByName(fieldName, toLink(fieldName, value));
            } else if (isLinkListField(fieldName) && value instanceof List) {
                List<Link<?>> ls = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    ls.add(toLink(fieldName, item));
                }
                setPropertyByName(fieldName, new LinkList(ls));
            } else if (isLinkMapField(fieldName) && value instanceof Map) {
                Class<?> keyType = linkKeyType(fieldName);
                Map<Object, Link<?>> lm = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    lm.put(DataHelper_I.convertType(e.getKey(), keyType), toLink(fieldName, e.getValue()));
                }
                setPropertyByName(fieldName, new LinkMap(lm));
            } else if (isEnumField(fieldName) && value instanceof String storedValue) {
                // Enum field (Phase 1, PRP-28) — see fromArcadeDocument for the rationale.
                setPropertyByName(fieldName, resolveEnumFromStorage(fieldName, storedValue));
            } else if (isNestedObjectField(fieldName)) {
                // Nested DataHelper object
                if (value instanceof Map) {
                    DataHelper_I<?> nested = createNestedObject(fieldName);
                    if (nested instanceof ArcadeDoc_I) {
                        ((ArcadeDoc_I<?>) nested).fromArcadeMap((Map<String, Object>) value);
                        setPropertyByName(fieldName, nested);
                    }
                }
            } else if (isListField(fieldName) && value instanceof List) {
                // List field
                List<?> sourceList = (List<?>) value;
                List<Object> targetList = new ArrayList<>();

                for (Object item : sourceList) {
                    if (item instanceof Map) {
                        DataHelper_I<?> listElement = createListElement(fieldName);
                        if (listElement instanceof ArcadeDoc_I) {
                            ((ArcadeDoc_I<?>) listElement).fromArcadeMap((Map<String, Object>) item);
                            targetList.add(listElement);
                        } else {
                            targetList.add(item);
                        }
                    } else {
                        targetList.add(item);
                    }
                }
                setPropertyByName(fieldName, targetList);
            } else if (isMapField(fieldName) && value instanceof Map) {
                // Map field
                Map<?, ?> sourceMap = (Map<?, ?>) value;
                Map<Object, Object> targetMap = (Map<Object, Object>) createMapInstance(fieldName);

                Class<?> keyType = getMapKeyType(fieldName);
                Class<?> valueType = getMapValueType(fieldName);

                for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                    Object key = DataHelper_I.convertType(entry.getKey(), keyType);
                    Object mapValue = entry.getValue();

                    if (isMapValueDataHelper(fieldName) && mapValue instanceof Map) {
                        DataHelper_I<?> mapValueElement = createMapValueElement(fieldName);
                        if (mapValueElement instanceof ArcadeDoc_I) {
                            ((ArcadeDoc_I<?>) mapValueElement).fromArcadeMap((Map<String, Object>) mapValue);
                            targetMap.put(key, mapValueElement);
                        } else {
                            targetMap.put(key, mapValue);
                        }
                    } else {
                        Object convertedValue = DataHelper_I.convertType(mapValue, valueType);
                        targetMap.put(key, convertedValue);
                    }
                }
                setPropertyByName(fieldName, targetMap);
            } else {
                // Simple field - convert and set
                Object convertedValue = DataHelper_I.convertType(value, fieldType);
                setPropertyByName(fieldName, convertedValue);
            }
        }

        return (E) this;
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

    // ========== Enum field support (Phase 1, PRP-28) — overridden by generated _A ==========

    /** True if the property is an enum-typed field with a declared {@code @AsUuid}/{@code @AsName} encoding. */
    default boolean isEnumField(String propertyName) { return false; }

    /**
     * Resolve a stored String (a uuid or a name, per the field's enum) back to its constant.
     * Never throws: an id matching no constant means the row is newer than this build, not that it
     * is corrupt, so {@code null} is returned. Default no-op; generated {@code _A} classes with enum
     * fields override with a real, reflection-free lookup.
     */
    default Object resolveEnumFromStorage(String propertyName, String storedValue) { return null; }

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
