package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.index.Index;
import datapotter.datahelper.DataHelper_I;

import java.util.ArrayList;
import java.util.List;

/**
 * Index health, and the one repair that works.
 *
 * <h3>The failure this addresses</h3>
 *
 * <p>An LSM index entry can outlive the record it points at. When that happens the engine surfaces it
 * as a {@link RecordNotFoundException} thrown from the <em>middle</em> of an iteration — so a single
 * bad entry does not degrade a query, it destroys it, and every verb that touches that type starts
 * failing at once.
 *
 * <p>What makes it nasty is that it cannot be repaired by the code that caused it. An orphaned entry
 * has no record behind it; a delete has nothing to load and therefore no property values with which to
 * locate the entry, and a re-insert writes new entries beside the stale ones rather than replacing
 * them. Deleting and re-writing every row of an affected type returned <em>byte-identical</em> orphan
 * counts. The data is fine, the index is lying, and only rebuilding the index from the records that
 * actually exist restores agreement between them.
 *
 * <p>{@link Delete} is how not to create the problem. This is how to get out of it.
 */
public final class Indexes {

    private Indexes() {}

    /**
     * Drop every index on these types and build them again from the records that are actually there.
     *
     * <p>This is a repair, not routine maintenance: it is O(rows) per type and it takes the indexes
     * away while it runs. It is also the only thing that clears an orphaned entry, because the rebuild
     * derives every entry from a record rather than trying to find and remove a stale one.
     *
     * <p>The definitions come back from {@link InitDoc#initDocTypes}, which is the same call that
     * created them, so there is no second place where an index shape is written down.
     *
     * @return how many indexes were dropped and recreated
     */
    @SafeVarargs
    public static int rebuild(Database db, TypeDef<? extends DataHelper_I<?>>... types) {
        var dropped = 0;
        for (var type : types) dropped += drop(db, type.definition().getSimpleName());
        InitDoc.initDocTypes(db, types);
        return dropped;
    }

    /**
     * Run a bulk change with the indexes taken down, and put them back afterwards.
     *
     * <p><b>This is the difference between seconds and minutes, and it is not a small factor.</b>
     * Deleting 50,000 rows of a five-index type measured 142 s as a plain SQL delete and 98 s
     * record-by-record: nearly all of it is LSM index maintenance, paid once per row per index. Dropping
     * the five indexes first costs 18 ms, the same delete then takes 2.9 s, and rebuilding them costs
     * 110 ms — <b>about 3 s against 142 s</b>, with identical results afterwards. Writes show the same
     * shape: bulk-loading rows and indexing once beats indexing each row as it lands.
     *
     * <p>Rebuilding is also what makes the result CORRECT rather than merely fast, since every entry is
     * then derived from a record that exists — see the class comment on orphaned entries.
     *
     * <h3>What this costs you while it runs</h3>
     *
     * <ul>
     *   <li><b>Queries on these types fall back to full scans</b>, because the indexes are not there.
     *       Correct, slower.</li>
     *   <li><b>Unique constraints are not enforced</b>, because a unique index is what enforces them. A
     *       duplicate written inside the window is not rejected — it surfaces when the rebuild tries to
     *       recreate the unique index and fails.</li>
     *   <li>So this is for a caller that serialises access to the database, and it is the wrong tool for
     *       a handful of rows: use {@link Delete#matching} there.</li>
     * </ul>
     *
     * <p>The rebuild runs in a {@code finally}: a change that throws half way leaves the data to the
     * caller's transaction semantics, but never leaves the type without its indexes.
     *
     * @return how many indexes were rebuilt
     */
    public static int duringBulkChange(Database db,
            List<TypeDef<? extends DataHelper_I<?>>> types, Runnable work) {
        var dropped = 0;
        try {
            for (var type : types) dropped += drop(db, type.definition().getSimpleName());
            work.run();
        } finally {
            InitDoc.initDocTypes(db, types.toArray(TypeDef[]::new));
        }
        return dropped;
    }

    /**
     * The shape of every index on a type, read off the live indexes.
     *
     * <p>Pairs with {@link #restore}. Unlike {@link #rebuild}, which recreates from the DECLARED schema
     * via {@link InitDoc}, this needs no {@link TypeDef} — so it works for a type this code did not
     * declare, and for {@link Rename}, which has to put back exactly what was there rather than what a
     * declaration says should be.
     */
    public static List<IndexDef> capture(Database db, String typeName) {
        var out = new ArrayList<IndexDef>();
        var schema = db.getSchema();
        if (!schema.existsType(typeName)) return out;
        for (Index index : schema.getType(typeName).getAllIndexes(false)) out.add(IndexDef.of(index));
        return out;
    }

    /**
     * Build captured indexes back onto a type, skipping any that are already there.
     *
     * <p>Skipping matters: after a failed rename the type may still carry an index the schema believes
     * in, and {@code createTypeIndex} treats that as an error rather than a no-op.
     *
     * @return how many were actually created
     */
    public static int restore(Database db, String typeName, List<IndexDef> defs) {
        var schema = db.getSchema();
        if (!schema.existsType(typeName)) return 0;
        var type = schema.getType(typeName);
        var created = 0;
        for (var def : defs)
            if (type.getIndexByProperties(def.properties().toArray(String[]::new)) == null) {
                def.createOn(db, typeName);
                created++;
            }
        return created;
    }

    /** Drop every index on one type, by name. Returns how many went. */
    public static int drop(Database db, String typeName) {
        var schema = db.getSchema();
        if (!schema.existsType(typeName)) return 0;
        var names = new ArrayList<String>();
        for (Index index : schema.getType(typeName).getAllIndexes(false)) names.add(index.getName());
        names.forEach(schema::dropIndex);
        return names.size();
    }

    /**
     * How many entries this query reaches whose record has gone — zero on a healthy index.
     *
     * <p>Costs a full pass over the matching entries, so it is a diagnostic to run when something looks
     * wrong or from a {@code doctor}-style command, not something to put on the query path.
     */
    public static long orphans(Query<?> query) {
        return count(query.documents());
    }

    /**
     * Every entry of a whole type, by name — the form a repair or {@code doctor} command wants, since
     * it needs no {@link TypeDef} generics and so can loop over a heterogeneous list of types.
     */
    public static long orphans(Database db, String typeName) {
        if (!db.getSchema().existsType(typeName)) return 0;
        return count(db.select().fromType(typeName).documents());
    }

    static long count(com.arcadedb.query.select.SelectIterator<?> it) {
        var orphans = 0L;
        try (it) {
            while (true) {
                try {
                    if (!it.hasNext()) break;
                    it.next();
                } catch (RecordNotFoundException e) {
                    orphans++;
                    /* The cursor normally steps past the entry it failed on. If it cannot, this would
                     * spin, so a run of failures with no progress ends the count. */
                    if (orphans > MAX_ORPHANS_COUNTED) break;
                }
            }
        }
        return orphans;
    }

    public static final long MAX_ORPHANS_COUNTED = 1_000_000L;

    /** Every index name on a type, for reporting what a rebuild would touch. */
    public static List<String> names(Database db, String typeName) {
        var out = new ArrayList<String>();
        var schema = db.getSchema();
        if (!schema.existsType(typeName)) return out;
        for (Index index : schema.getType(typeName).getAllIndexes(false)) out.add(index.getName());
        return out;
    }
}
