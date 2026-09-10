package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Immutable snapshot of a <b>reference</b> to another {@code @ArcadeData} record.
 *
 * <p>A {@code Link<T>} holds the target's identity (RID) and, optionally, a projected snapshot of
 * the target — exactly what the query returned about the reference and nothing more. It mirrors
 * ArcadeDB's own SQL semantics: {@code SELECT customer} returns the link as a bare RID (unresolved
 * here), while {@code SELECT customer:{...}} returns it as a partial object (resolved here).
 *
 * <p>There is no lazy faulting: {@link #get()} never hits the database. The single place I/O can
 * happen is {@link #resolve(Database)}, which is explicit and takes a {@code Database}.
 *
 * <p><b>Zero reflection:</b> resolution instantiates the target through a {@link Supplier} factory
 * (a {@code Target::new} constructor reference the generated code supplies), never via reflection.
 * Links whose target type the carrier can't know — e.g. edge endpoints — carry no factory and must
 * be resolved with {@link #resolve(Database, Supplier)}.
 *
 * @param <T> the referenced (target) entity type
 */
public final class Link<T extends ArcadeDoc_I<T>> {

    private final String rid;          // identity, e.g. "#12:3"; null only when the link is unset
    private final T target;            // projected snapshot, or null when not projected
    private final Supplier<T> factory; // zero-reflection target factory (Target::new), or null

    private Link(String rid, T target, Supplier<T> factory) {
        this.rid = rid;
        this.target = target;
        this.factory = factory;
    }

    /** An unresolved link to {@code rid} carrying a factory so it can {@link #resolve(Database)}. */
    public static <T extends ArcadeDoc_I<T>> Link<T> ofRid(String rid, Supplier<T> factory) {
        return new Link<>(rid, null, factory);
    }

    /** An unresolved link to {@code rid} with no factory; use {@link #resolve(Database, Supplier)}. */
    public static <T extends ArcadeDoc_I<T>> Link<T> ofRid(String rid) {
        return new Link<>(rid, null, null);
    }

    /**
     * A link built from a loaded/projected target object: its identity is read from
     * {@link ArcadeDoc_I#$rid()} and it is already resolved. Returns {@code null} for a null target.
     */
    public static <T extends ArcadeDoc_I<T>> Link<T> of(T target) {
        if (target == null) return null;
        return new Link<>(target.$rid(), target, null);
    }

    /** A resolved link carrying both identity and a (possibly partial) projected target. */
    public static <T extends ArcadeDoc_I<T>> Link<T> resolved(String rid, T target) {
        return new Link<>(rid, target, null);
    }

    /** The target's RID, e.g. {@code "#12:3"}; {@code null} only if the link is unset. */
    public String rid() { return rid; }

    /** Whether this link references anything (has an identity). */
    public boolean isSet() { return rid != null; }

    /** Whether the target snapshot is present (the query projected it). */
    public boolean isResolved() { return target != null; }

    /**
     * The projected target snapshot. Never performs I/O.
     *
     * @throws UnresolvedLinkException if the target was not projected (use {@link #resolve(Database)})
     */
    public T get() {
        if (target == null) throw new UnresolvedLinkException(rid);
        return target;
    }

    /** The projected target if present, else empty. Never performs I/O. */
    public Optional<T> opt() { return Optional.ofNullable(target); }

    /**
     * Explicitly fetch the target from the database by RID — the one I/O entry point. Returns the
     * already-projected target if present; {@code null} if the link is unset or the record is gone.
     * Instantiates the target via the carrier's zero-reflection factory.
     *
     * @throws IllegalStateException if the link carries no factory (e.g. an edge endpoint); resolve
     *                               such links with {@link #resolve(Database, Supplier)} instead.
     */
    public T resolve(Database db) {
        if (target != null) return target;
        if (rid == null) return null;
        if (factory == null)
            throw new IllegalStateException("Link " + rid + " has no target factory; "
                + "resolve it with resolve(db, Target::new).");
        final Document doc = RID.create(db, rid).asDocument(true);
        if (doc == null) return null;
        final T t = factory.get();
        if (t == null) return null;
        t.fromArcadeDocument(doc);
        return t;
    }

    /**
     * Explicitly fetch the target, supplying its type as a zero-reflection factory
     * ({@code Target::new}). For links whose type the carrier doesn't know — edge endpoints, raw RIDs.
     */
    @SuppressWarnings("unchecked")
    public <X extends ArcadeDoc_I<X>> X resolve(Database db, Supplier<X> factory) {
        if (target != null) return (X) target;
        if (rid == null) return null;
        final Document doc = RID.create(db, rid).asDocument(true);
        if (doc == null) return null;
        final X x = factory.get();
        x.fromArcadeDocument(doc);
        return x;
    }

    @Override
    public String toString() {
        return "Link[" + rid + (target != null ? " (resolved)" : "") + "]";
    }
}
