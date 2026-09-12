package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;

import java.util.List;

/**
 * Renaming a type, without silently destroying its indexes.
 *
 * <h3>The failure this exists to prevent</h3>
 *
 * <p>On ArcadeDB up to and including 26.8.1, {@code DocumentType.rename(...)} — and the equivalent
 * {@code ALTER TYPE .. NAME} — leave the index FILE under the old type's name while the schema goes on
 * recording that name under the new type. In memory everything looks correct: {@code getIndexByProperties}
 * still answers. At the next open the loader cannot find the file and skips it, logging one line:
 *
 * <pre>WARNI [LocalSchema] Cannot find indexes [Old_0_1355095139772500] defined in type 'New'. Ignoring them</pre>
 *
 * <p><b>That is a dropped CONSTRAINT, not a slow query.</b> Measured on a UNIQUE index over five rows:
 * after the rename and a reopen, inserting a value that already existed was ACCEPTED and the type went
 * to six rows. Nothing reported it.
 *
 * <p>It cannot be repaired afterwards. Recreating the index fails with
 * {@code Found the existent index 'New[a]'} — the schema still believes in the entry that is about to
 * be discarded. So the repair has to happen BEFORE the rename, which is the whole reason this class
 * exists rather than a comment telling people to be careful.
 *
 * <h3>Why it stays even though the engine is fixed</h3>
 *
 * <p>Fixed upstream in 26.9.1 (ArcadeData/arcadedb#6103, #6099). This wrapper is correct on both, costs
 * one index rebuild either way, and is what lets a consumer pin whichever engine version they like
 * without inheriting a data-integrity bug. A library does not get to assume its users upgrade.
 */
public final class Rename {

    private Rename() {}

    /**
     * Rename a type and keep its indexes, in the only order that works.
     *
     * <p>Capture the index shapes, drop them while the schema and the files still agree, rename, then
     * build them again against the new name.
     *
     * <p><b>Not free, and the plan shown to a user should say so.</b> The rename itself is O(1); every
     * index is then rebuilt, which is a full scan of the type each. On a large type this is the cost of
     * the whole operation.
     *
     * <p>The rebuild runs in a {@code finally}, so a rename that throws does not leave the type without
     * its indexes — but note the indexes come back on whichever name the type currently carries, which
     * is the old one if the rename is what failed.
     *
     * @return the index definitions that were rebuilt, for reporting
     * @throws IllegalArgumentException if {@code from} does not exist, or {@code to} already does
     */
    public static List<IndexDef> type(Database db, String from, String to) {
        var schema = db.getSchema();
        if (!schema.existsType(from))
            throw new IllegalArgumentException("Cannot rename '%s': no such type".formatted(from));
        if (schema.existsType(to))
            throw new IllegalArgumentException(
                    "Cannot rename '%s' to '%s': that type already exists".formatted(from, to));

        var captured = Indexes.capture(db, from);
        var renamed = false;
        try {
            Indexes.drop(db, from);
            schema.getType(from).rename(to);
            renamed = true;
        } finally {
            Indexes.restore(db, renamed ? to : from, captured);
        }
        return captured;
    }
}
