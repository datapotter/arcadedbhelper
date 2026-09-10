package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.exception.RecordNotFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed bulk delete — the third verb beside {@link Query} (read) and {@link Upsert} (write).
 *
 * <pre>
 * import static datapotter.arcadedbhelper.Query.query;
 *
 * long gone = Delete.matching(query(db, JRef.TYPEDEF).eq($projectId, pid));
 * </pre>
 *
 * <h3>Why this exists rather than {@code db.command("sql", "DELETE FROM X WHERE ...")}</h3>
 *
 * <p>Because that does not survive scale, and the way it fails is silent. Removing roughly 370,000 rows
 * from one type left the LSM indexes holding entries for records that were gone. Nothing complained at
 * the time; every <em>later</em> indexed read then threw
 * {@code RecordNotFoundException: Record #6:1179648 not found} from the middle of an iteration, so a
 * query that had worked for a year became a stack trace. Worse, the damage is self-perpetuating: an
 * orphaned entry has no record behind it, so a re-run of the same delete cannot find it and cannot
 * remove it. Re-indexing the data returned byte-identical orphan counts. Only {@link Indexes#rebuild}
 * clears it.
 *
 * <p>So each record is <b>loaded and deleted through {@link Database#deleteRecord}</b>. Loading is not
 * incidental: the engine needs the record's own property values to know which index entries to remove.
 *
 * <h3>And why the identities are collected first</h3>
 *
 * <p>The obvious shape — {@code SELECT ... WHERE k = ? LIMIT 2000} in a loop until nothing comes back —
 * re-scans from the beginning every round. Deleting 50,000 rows that way measured <b>82 seconds</b>.
 * One indexed pass to collect the identities, then bounded transactions to delete them, is the same
 * work once. The list costs about 24 bytes a row, which is the trade being made.
 *
 * <p>Transactions are bounded at {@link #DEFAULT_BATCH} rather than being one enormous unit of work,
 * for the ordinary reason: a failure should not roll back an hour.
 */
public final class Delete {

    private Delete() {}

    /** Rows per transaction. Large enough to amortise commit cost, small enough to be a cheap loss. */
    public static final int DEFAULT_BATCH = 2000;

    /**
     * Delete every row the query matches, and report how many records actually went.
     *
     * <p>The query's conditions select the rows; its {@code orderBy}/{@code skip} are irrelevant here,
     * though {@code limit} is honoured and is a reasonable way to delete in stages.
     */
    public static <E extends ArcadeDoc_I<E>> long matching(Query<E> query) {
        return matching(query, DEFAULT_BATCH);
    }

    public static <E extends ArcadeDoc_I<E>> long matching(Query<E> query, int batchSize) {
        return rids(query.database(), identities(query), batchSize);
    }

    /**
     * The identities the query matches, in one pass.
     *
     * <p>Stops early and quietly at an entry whose record has already gone. Such an entry cannot be
     * deleted here — there is no record to load, and therefore no property values with which to find
     * the index entries that point at it — so continuing past it would only produce a longer list of
     * things this method cannot fix. {@link Indexes#rebuild} is what fixes it.
     */
    public static List<RID> identities(Query<?> query) {
        var out = new ArrayList<RID>();
        try (var it = query.documents()) {
            while (true) {
                try {
                    if (!it.hasNext()) break;
                    out.add(it.next().getIdentity());
                } catch (RecordNotFoundException orphanedIndexEntry) {
                    break;
                }
            }
        }
        return out;
    }

    /** Delete these identities in bounded transactions. Absent records are counted as already gone. */
    public static long rids(Database db, List<RID> rids, int batchSize) {
        var batch = Math.max(1, batchSize);
        var deleted = 0L;
        for (var from = 0; from < rids.size(); from += batch) {
            var chunk = rids.subList(from, Math.min(rids.size(), from + batch));
            var count = new long[1];
            db.transaction(() -> chunk.forEach(rid -> {
                if (deleteOne(db, rid)) count[0]++;
            }));
            deleted += count[0];
        }
        return deleted;
    }

    /** Loaded with content, because that is what tells the engine which index entries to remove. */
    static boolean deleteOne(Database db, RID rid) {
        try {
            db.deleteRecord(db.lookupByRID(rid, true));
            return true;
        } catch (RecordNotFoundException alreadyGone) {
            return false;
        }
    }
}
