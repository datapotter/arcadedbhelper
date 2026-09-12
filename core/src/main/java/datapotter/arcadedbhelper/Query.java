package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.select.Select;
import com.arcadedb.query.select.SelectIterator;
import com.arcadedb.query.select.SelectWhereAfterBlock;
import com.arcadedb.query.select.SelectWhereLeftBlock;
import datapotter.datahelper.DataField;
import datapotter.datahelper.EnumField;
import datapotter.datahelper.EnumListField;
import datapotter.datahelper.Field;
import datapotter.datahelper.Field_I;
import datapotter.datahelper.HasUuid;
import datapotter.datahelper.LinkField;
import datapotter.datahelper.LinkListField;
import datapotter.datahelper.LinkMapField;
import datapotter.datahelper.ListDataField;
import datapotter.datahelper.MapDataField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Typed reads — the counterpart to {@link Upsert}, which is the typed write.
 *
 * <p>Without this the read side is the one place the whole library drops back to string literals:
 * {@code db.select().fromType("DriveFile").where().property("driveId").eq().value(id)}. Those strings
 * survive a rename, defeat find-usages, and — the part that actually costs — bind nothing, so
 * {@code property("size").eq().value("big")} compiles happily against a {@code Long} column.
 *
 * <p>Here the field carries its own type, so field and value are bound by the generic signature and
 * the mismatch is a compile error:
 *
 * <pre>
 * import static datapotter.arcadedbhelper.Query.query;
 * import static com.example.model.DriveFile_A.*;      // $driveId, $modifiedTime, $name
 *
 * // Lazy by default — a Query IS an Iterable, so nothing is buffered and break stops the scan.
 * for (DriveFile f : query(db, DriveFile.TYPEDEF).eq($driveId, driveId)) {
 *     process(f);
 * }
 *
 * List&lt;DriveFile&gt; recent = query(db, DriveFile.TYPEDEF)
 *         .eq($driveId, driveId)
 *         .gt($modifiedTime, since)
 *         .orderByDesc($modifiedTime)
 *         .limit(20)
 *         .list();                      // eager, and bounded by limit(20)
 *
 * DriveFile one = query(db, DriveFile.TYPEDEF).eq($fileId, id).firstOrNull();
 * long n       = query(db, DriveFile.TYPEDEF).eq($folder, true).count();
 * </pre>
 *
 * <p>The entity type comes from the {@link TypeDef}, and so does the {@code X::new} factory that
 * materialises each row — see {@link TypeDef#factory()}. Declaring it once on the model is what keeps
 * the constructor reference out of every call site while staying reflection-free.
 *
 * <p><b>Nothing here buffers a result set unless you ask it to.</b> {@link #iterator() for-each},
 * {@link #stream()}, {@link #firstOrNull()}, {@link #count()} and {@link #exists()} all pull rows as
 * needed; {@link #list()} is the one eager terminal, and is named so you can see that at the call
 * site. {@link #documents()} and {@link #vertices()} hand back the engine's own lazy iterator for code
 * that wants the raw record and no per-row object at all — the right choice when a bulk pass reads two
 * fields out of hundreds of thousands of rows.
 *
 * <h3>Two limits inherited from the engine, stated rather than hidden</h3>
 *
 * <p><b>No parentheses and no NOT.</b> Conditions join with AND by default, {@link #or()} switches the
 * next one to OR, and precedence is SQL's — {@code a AND b OR c} means {@code (a AND b) OR c}. A
 * genuine {@code (a OR b) AND (c OR d)} cannot be expressed by the engine's builder at all; use
 * {@code db.query("sql", ...)} for that case alone.
 *
 * <p><b>Nested embedded fields cannot be filtered</b> — the executor re-evaluates candidates with a
 * flat {@code record.get()}, so a dotted path matches nothing and, worse, fails <em>silently</em> by
 * returning an empty result. Rather than pass that through, this class rejects such a field with an
 * {@link IllegalArgumentException} naming the working alternatives. That check is the one place this
 * wrapper is strictly better than the raw builder, not merely tidier.
 */
public final class Query<E extends ArcadeDoc_I<E>> implements Iterable<E> {

    private final Database db;
    private final String typeName;
    private final Supplier<E> factory;

    private final List<Cond> conds = new ArrayList<>();
    private final List<Object[]> orders = new ArrayList<>();   // {String field, Boolean ascending}
    private boolean nextIsOr = false;
    private int limit = -1;
    private int skip = -1;
    private Boolean polymorphic = null;
    private boolean skipUnreadable = false;
    private long unreadable = 0;

    private enum Op { EQ, NEQ, LT, LE, GT, GE, LIKE, ILIKE, IN, BETWEEN, IS_NULL, IS_NOT_NULL }

    private record Cond(String field, Op op, Object value, Object value2, boolean or) {
    }

    private Query(Database db, String typeName, Supplier<E> factory) {
        this.db = db;
        this.typeName = typeName;
        this.factory = factory;
    }

    /**
     * Start a query against {@code type}, materialising rows with the factory the type definition
     * carries.
     *
     * <p>This is the normal form. It needs the model's {@code TYPEDEF} to declare
     * {@code .factory(X::new)} — one line, once, in the model rather than at every call site.
     *
     * @throws IllegalArgumentException if the type definition declares no factory, naming both fixes
     */
    public static <E extends ArcadeDoc_I<E>> Query<E> query(Database db, TypeDef<E> type) {
        Supplier<E> f = type.factory();
        if (f == null) {
            throw new IllegalArgumentException(
                    type.definition().getSimpleName() + ".TYPEDEF declares no factory, so rows cannot "
                    + "be materialised without reflection. Add .factory("
                    + type.definition().getSimpleName() + "::new) to its schemaBuilder() chain, or pass "
                    + "the factory here with query(db, TYPEDEF, "
                    + type.definition().getSimpleName() + "::new).");
        }
        return new Query<>(db, type.definition().getSimpleName(), f);
    }

    /**
     * Start a query with an explicit factory, for a type definition that does not declare one — and
     * for the case where rows should be materialised as some other compatible type.
     */
    public static <E extends ArcadeDoc_I<E>> Query<E> query(Database db, TypeDef<E> type,
            Supplier<E> factory) {
        return new Query<>(db, type.definition().getSimpleName(), factory);
    }

    // ---- conditions -------------------------------------------------------

    /**
     * Join the <b>next</b> condition with OR instead of AND. Remember the engine's fixed precedence:
     * AND binds tighter, and there is no grouping.
     */
    public Query<E> or() {
        nextIsOr = true;
        return this;
    }

    /** Explicit AND for the next condition. This is already the default; use it for readability. */
    public Query<E> and() {
        nextIsOr = false;
        return this;
    }

    public <T> Query<E> eq(Field_I<E, T> field, T value)  { return add(field, Op.EQ, value, null); }
    public <T> Query<E> neq(Field_I<E, T> field, T value) { return add(field, Op.NEQ, value, null); }
    public <T> Query<E> lt(Field_I<E, T> field, T value)  { return add(field, Op.LT, value, null); }
    public <T> Query<E> le(Field_I<E, T> field, T value)  { return add(field, Op.LE, value, null); }
    public <T> Query<E> gt(Field_I<E, T> field, T value)  { return add(field, Op.GT, value, null); }
    public <T> Query<E> ge(Field_I<E, T> field, T value)  { return add(field, Op.GE, value, null); }

    /** SQL {@code LIKE} with {@code %} wildcards. String fields only. */
    public Query<E> like(Field_I<E, String> field, String pattern) {
        return add(field, Op.LIKE, pattern, null);
    }

    /** Case-insensitive {@code LIKE}. String fields only. */
    public Query<E> ilike(Field_I<E, String> field, String pattern) {
        return add(field, Op.ILIKE, pattern, null);
    }

    /** Membership. An empty collection is a condition nothing satisfies, which is what it says. */
    public <T> Query<E> in(Field_I<E, T> field, Collection<T> values) {
        return add(field, Op.IN, values == null ? List.of() : List.copyOf(values), null);
    }

    /** Inclusive range — {@code low <= field <= high}. */
    public <T> Query<E> between(Field_I<E, T> field, T low, T high) {
        return add(field, Op.BETWEEN, low, high);
    }

    public Query<E> isNull(Field_I<E, ?> field)    { return add(field, Op.IS_NULL, null, null); }
    public Query<E> isNotNull(Field_I<E, ?> field) { return add(field, Op.IS_NOT_NULL, null, null); }

    // ---- modifiers --------------------------------------------------------

    /** Add a sort key. Call again for secondary keys, in order. */
    public Query<E> orderBy(Field_I<E, ?> field, boolean ascending) {
        orders.add(new Object[]{field.name(), ascending});
        return this;
    }

    public Query<E> orderByAsc(Field_I<E, ?> field)  { return orderBy(field, true); }
    public Query<E> orderByDesc(Field_I<E, ?> field) { return orderBy(field, false); }

    public Query<E> limit(int n) { this.limit = n; return this; }
    public Query<E> skip(int n)  { this.skip = n; return this; }

    /** Exclude subtypes. The engine includes them by default. */
    public Query<E> polymorphic(boolean include) { this.polymorphic = include; return this; }

    /**
     * Skip index entries whose record has gone, instead of throwing.
     *
     * <p>Without this, one orphaned entry does not degrade a result — it destroys it, because the
     * {@link RecordNotFoundException} comes out of the middle of the iteration and takes the whole
     * query with it. For anything user-facing that is the wrong trade: a short answer beats a stack
     * trace, <b>provided the shortfall is reported</b>. It is not reported here, deliberately; ask
     * {@link #unreadableSkipped()} afterwards and say so in your own words, because silently dropping
     * rows is worse than either alternative.
     *
     * <pre>
     * var q = query(db, JRef.TYPEDEF).eq($projectId, pid).skippingUnreadable();
     * var rows = q.list();
     * if (q.unreadableSkipped() &gt; 0) warn(q.unreadableSkipped() + " rows unreadable; run the repair");
     * </pre>
     *
     * <p>{@link Indexes#rebuild} is the repair; {@link Indexes#orphans} counts without materialising.
     * Note that {@link #count()} and {@link #exists()} are answered by the engine from the index and so
     * still count orphans — that disagreement is itself a useful signal.
     */
    public Query<E> skippingUnreadable() { this.skipUnreadable = true; return this; }

    /** How many unreadable entries the last traversal skipped. Meaningless without {@link #skippingUnreadable()}. */
    public long unreadableSkipped() { return unreadable; }

    // ---- terminals --------------------------------------------------------

    /**
     * Iterate results <b>lazily</b> — one row is fetched and materialised at a time, nothing is
     * buffered, and stopping early stops the work.
     *
     * <pre>
     * for (DriveCorpus c : query(db, DriveCorpus.TYPEDEF, DriveCorpus::of).eq($state, "stale")) {
     *     if (enough(c)) break;      // the rest is never read
     * }
     * </pre>
     *
     * <p>This is the default way to consume a query and should be preferred over {@link #list()} for
     * anything unbounded. Breaking out early is safe: the engine's {@code SelectIterator} holds no
     * resource that needs releasing, so there is nothing to leak.
     */
    @Override
    public java.util.Iterator<E> iterator() {
        final SelectIterator<Document> it = documents();
        unreadable = 0;
        return new java.util.Iterator<>() {
            private Document pending;
            private boolean exhausted;

            /** Pre-fetches, because deciding whether a row can be READ is what may fail. */
            @Override public boolean hasNext() {
                if (pending != null) return true;
                while (!exhausted) {
                    try {
                        if (!it.hasNext()) {
                            exhausted = true;
                            return false;
                        }
                        pending = it.next();
                        return true;
                    } catch (RecordNotFoundException orphanedIndexEntry) {
                        if (!skipUnreadable) throw orphanedIndexEntry;
                        unreadable++;
                        /* The cursor steps past the entry it failed on. If it ever does not, this would
                         * spin forever, so an implausible run of failures ends the traversal. */
                        if (unreadable > Indexes.MAX_ORPHANS_COUNTED) exhausted = true;
                    }
                }
                return false;
            }

            @Override public E next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                var d = pending;
                pending = null;
                return materialize(d);
            }
        };
    }

    /**
     * Every matching row, materialised into a list up front.
     *
     * <p><b>Eager.</b> Use it when the result set is bounded and you need it more than once, or need
     * its size; reach for the {@link #iterator() for-each} form or {@link #stream()} otherwise, since
     * both stay lazy and neither holds the whole result in memory.
     */
    public List<E> list() {
        if (skipUnreadable) {
            var lenient = new ArrayList<E>();
            for (E e : this) lenient.add(e);
            return lenient;
        }
        var out = new ArrayList<E>();
        try (var it = documents()) {
            while (it.hasNext()) out.add(materialize(it.next()));
        }
        return out;
    }

    /** The first matching row, or empty. */
    public Optional<E> first() {
        return Optional.ofNullable(firstOrNull());
    }

    /** The first matching row, or {@code null} — the form that reads best for a keyed lookup. */
    public E firstOrNull() {
        if (skipUnreadable) {
            var it = iterator();
            return it.hasNext() ? it.next() : null;
        }
        try (var it = documents()) {
            var d = it.nextOrNull();
            return d == null ? null : materialize(d);
        }
    }

    /**
     * Matching rows as a <b>lazy</b> stream — rows are pulled and materialised as the stream is
     * consumed, so a short-circuiting terminal ({@code findFirst}, {@code anyMatch}, {@code limit})
     * stops the underlying scan rather than paying for the whole result set.
     */
    public Stream<E> stream() {
        if (skipUnreadable) return java.util.stream.StreamSupport.stream(spliterator(), false);
        return documents().stream().map(this::materialize);
    }

    /** How many rows match. Honours {@link #limit(int)}. */
    public long count() {
        Object c = build();
        return c instanceof Select s ? s.count() : ((SelectWhereAfterBlock) c).count();
    }

    /** Whether anything matches. */
    public boolean exists() {
        Object c = build();
        return c instanceof Select s ? s.exists() : ((SelectWhereAfterBlock) c).exists();
    }

    /** Raw documents — the escape hatch for code that wants the engine's own record. */
    public SelectIterator<Document> documents() {
        Object c = build();
        return c instanceof Select s ? s.documents() : ((SelectWhereAfterBlock) c).documents();
    }

    /** Raw vertices, for a {@code @ArcadeData(type = VERTEX)} type — pairs with {@link Traverse}. */
    public SelectIterator<Vertex> vertices() {
        Object c = build();
        return c instanceof Select s ? s.vertices() : ((SelectWhereAfterBlock) c).vertices();
    }

    /** The first matching vertex, or {@code null}. The usual start of a traversal. */
    public Vertex firstVertex() {
        try (var it = vertices()) {
            return it.nextOrNull();
        }
    }

    // ---- internals --------------------------------------------------------

    /** The database this query runs against, so {@link Delete} need not be handed it a second time. */
    Database database() { return db; }

    /** One row into one object, through the type's own constructor. No reflection. */
    private E materialize(Document d) {
        return factory.get().fromArcadeDocument(d);
    }

    private Query<E> add(Field_I<E, ?> field, Op op, Object value, Object value2) {
        rejectNested(field);
        // Enum-aware (Phase 1, PRP-28): the engine compares against the stored uuid/name string, not
        // the Java constant. Covers eq/neq/lt/le/gt/ge/between (value2) and in (a Collection value).
        conds.add(new Cond(field.name(), op, HasUuid.storageValue(value), HasUuid.storageValue(value2), nextIsOr));
        nextIsOr = false;
        return this;
    }

    /**
     * Refuse a field the engine cannot actually filter on. Filtering an embedded object, or a list or
     * map of them, returns an empty result instead of an error, so the caller reads "no matches" and
     * believes it. Fail loudly here instead, and name the ways out.
     */
    /**
     * Refuse a null check that shares a query with another condition and a limit or skip.
     *
     * <p>The engine's native executor takes candidates from the other condition's index, truncates
     * them to the limit, and only then evaluates IS NULL / IS NOT NULL — so the query returns a
     * fraction of the matches, silently, with no error. Measured on ArcadeDB 26.7.3 with 1000 rows of
     * which 200 match: {@code eq + isNotNull} at limit 500 returned 101, at limit 200 returned 41, at
     * limit 50 returned 11. The same query in {@code db.query("sql", ...)} returns 200 every time, so
     * this is an inconsistency between two of the engine's own APIs rather than a limit of the data.
     *
     * <p>Left unguarded this is the worst kind of defect: a plausible number, a green build, and a
     * caller who believes it. A {@link Delete} built from such a query would delete the wrong subset.
     *
     * <p>Deliberately not refused: a null check that is the <b>only</b> condition. There the engine
     * drives the scan from that field's own index, which holds none of the null entries, so every
     * candidate is already a match and truncation cannot lose one — verified returning 200 of 200.
     * That case does assume the field is indexed; if a future report shows it wrong without an index,
     * widen this guard to every null predicate that meets a limit.
     */
    private void rejectNullPredicateWithPaging() {
        if ((limit < 0 && skip < 0) || conds.size() < 2) return;
        for (Cond c : conds) {
            if (c.op() != Op.IS_NULL && c.op() != Op.IS_NOT_NULL) continue;
            throw new IllegalArgumentException(
                    "Cannot combine " + c.op() + " on '" + c.field() + "' with another condition and a "
                    + "limit/skip: the engine applies the limit to candidates before evaluating the null "
                    + "check, so this would silently return fewer rows than actually match. Options: drop "
                    + "the limit and bound the result in Java, put the null check in its own query, or "
                    + "use db.query(\"sql\", ...), which evaluates it correctly.");
        }
    }

    /**
     * Exhaustive over the sealed {@link Field_I} rather than a test for the three nested shapes, so
     * a new descriptor cannot quietly default to filterable. Every {@code false} here is a claim
     * that the engine can evaluate the field with a flat {@code record.get()} — true of a RID and
     * true of an enum, which crosses the boundary as one string.
     */
    private boolean isNested(Field_I<E, ?> field) {
        return switch (field) {
            case DataField<?, ?> dataField -> true;
            case ListDataField<?, ?> listField -> true;
            case MapDataField<?, ?, ?> mapField -> true;
            case LinkField<?, ?> linkField -> false;
            case LinkListField<?, ?> linkList -> false;
            case LinkMapField<?, ?, ?> linkMap -> false;
            case EnumField<?, ?> enumField -> false;
            case EnumListField<?, ?> enumListField -> false;
            case Field<?, ?> plain -> false;
        };
    }

    private void rejectNested(Field_I<E, ?> field) {
        if (isNested(field)) {
            throw new IllegalArgumentException(
                    "Cannot filter on '" + field.name() + "': it is a nested/embedded field, and the "
                    + "engine's executor re-reads candidates with a flat record.get(), so the query "
                    + "would silently return nothing rather than fail. Options: post-filter in Java, "
                    + "model the relationship as an edge, denormalise a flat indexed field, or drop to "
                    + "db.query(\"sql\", ...).");
        }
    }

    /**
     * Replay the accumulated conditions onto the engine's builder.
     *
     * <p>Returns whichever block the chain ended on: a bare {@link Select} when there are no
     * conditions and no modifiers were applied through the where-chain, otherwise a
     * {@link SelectWhereAfterBlock}. Both carry the same terminals, so the callers above dispatch on
     * the type. Conditions are held and replayed rather than applied as they arrive so that
     * {@link #rejectNested} can run before the engine is touched at all.
     */
    private Object build() {
        rejectNullPredicateWithPaging();
        Select s = db.select().fromType(typeName);
        if (polymorphic != null) s = s.polymorphic(polymorphic);

        if (conds.isEmpty()) {
            for (Object[] o : orders) s = s.orderBy((String) o[0], (Boolean) o[1]);
            if (skip >= 0) s = s.skip(skip);
            if (limit >= 0) s = s.limit(limit);
            return s;
        }

        SelectWhereAfterBlock after = null;
        SelectWhereLeftBlock left = s.where();
        for (Cond c : conds) {
            if (after != null) left = c.or() ? after.or() : after.and();
            var op = left.property(c.field());
            after = switch (c.op()) {
                case EQ      -> op.eq().value(c.value());
                case NEQ     -> op.neq().value(c.value());
                case LT      -> op.lt().value(c.value());
                case LE      -> op.le().value(c.value());
                case GT      -> op.gt().value(c.value());
                case GE      -> op.ge().value(c.value());
                case LIKE    -> op.like().value(c.value());
                case ILIKE   -> op.ilike().value(c.value());
                case IN      -> op.in().value(c.value());
                case BETWEEN -> op.between().values(c.value(), c.value2());
                case IS_NULL -> op.isNull();
                case IS_NOT_NULL -> op.isNotNull();
            };
        }

        // Modifiers hang off the where-chain and hand back a Select; apply them in the engine's
        // required order (orderBy/skip/limit after every condition).
        if (orders.isEmpty() && skip < 0 && limit < 0) return after;

        Select t = null;
        for (Object[] o : orders) {
            t = t == null ? after.orderBy((String) o[0], (Boolean) o[1]) : t.orderBy((String) o[0], (Boolean) o[1]);
        }
        if (skip >= 0)  t = t == null ? after.skip(skip)   : t.skip(skip);
        if (limit >= 0) t = t == null ? after.limit(limit) : t.limit(limit);
        return t;
    }
}
