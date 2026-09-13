package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@link InitDoc#initDocTypes} found but did not apply (PRP-28 phase 3) — the crash-first,
 * plan-then-apply half of the design. Everything already SAFE (a type rename via the {@link Rename}
 * wrapper, a new type or property, first-time id adoption, an orphan mark) already happened by the
 * time this object exists: it carries only what rewrites every row of a type, and therefore needs a
 * backup, a reviewed plan, an explicit call to {@link #apply}, and a journalled recipe before any of
 * it touches anything.
 *
 * <p>An empty plan is the common case and is not a failure of anything — most init runs detect
 * nothing to migrate, and {@link #isEmpty()} is how a caller tells the difference between "checked,
 * nothing pending" and "did not check."
 */
public final class MigrationPlan {

    private final List<PropertyRenamePlan> propertyRenames;

    MigrationPlan(List<PropertyRenamePlan> propertyRenames) {
        this.propertyRenames = List.copyOf(propertyRenames);
    }

    /** Property renames matched by id, detected but not applied. */
    public List<PropertyRenamePlan> propertyRenames() {
        return propertyRenames;
    }

    public boolean isEmpty() {
        return propertyRenames.isEmpty();
    }

    /** Print what would happen. Never applies anything — reviewing a plan is not acting on it. */
    public void print() {
        if (isEmpty()) {
            System.out.println("Migration plan: nothing pending.");
            return;
        }
        System.out.println("Migration plan — " + propertyRenames.size()
                + " property rename(s) detected, none applied yet:");
        for (PropertyRenamePlan r : propertyRenames) System.out.println("  " + r);
        System.out.println("Call MigrationPlan.apply(db, backupDestination) to apply them.");
    }

    /**
     * Back up, then apply every pending rename in turn, journalling each recipe step so a crash
     * mid-run resumes rather than restarts. One rename's failure does not stop the others — each is
     * independent, matched on its own id, and journalled under its own entry.
     *
     * <p><b>This is the ONLY method in this design that rewrites data.</b> Everything upstream of it
     * — detection, the plan, {@link #print} — is read-only.
     *
     * @param backupDestination where the pre-migration backup archive is written
     * @return one line per attempted rename: {@code "OK <plan>"} or {@code "FAILED <plan>: <message>"}
     */
    public List<String> apply(Database db, Path backupDestination) {
        if (isEmpty()) return List.of();

        Backups.take(db, backupDestination);

        MigrationJournal journal = new MigrationJournal(Path.of(db.getDatabasePath()));
        List<String> results = new ArrayList<>();
        for (PropertyRenamePlan plan : propertyRenames) {
            try {
                PropertyMigration.applyRename(db, plan, journal);
                results.add("OK " + plan);
            } catch (RuntimeException e) {
                results.add("FAILED " + plan + ": " + e.getMessage());
            }
        }
        return results;
    }
}
