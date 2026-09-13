package datapotter.arcadedbhelper;

import com.arcadedb.schema.Schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Records which step of a property-rename recipe has completed, so a crash mid-recipe resumes from
 * the right place instead of re-running an already-applied, non-idempotent step (PRP-28 phase 3).
 * ALSO carries the index definitions captured off the OLD property before they are dropped, because
 * once they are dropped the live schema can no longer answer what they were — a resume that only
 * remembered a step number would have nothing left to rebuild step 6 from.
 *
 * <p><b>Why this is not a row in the database being migrated.</b> The crash this exists for is
 * exactly the one that might leave that database inconsistent, and asking the thing that might be
 * broken what already happened to it is the wrong design — the same reasoning that keeps
 * {@link Backups} outside the database it backs up. A plain file survives independently of whatever
 * state the migration left the schema in.
 *
 * <p>One file per rename, named after what it renames, so two unrelated renames in the same run
 * cannot collide or be confused after a restart. Deleted once the whole recipe finishes — after
 * that the schema itself is the record (the property really is renamed), so there is nothing left
 * to resume.
 *
 * <p><b>Ordering is load-bearing and this class does not decide it.</b> {@link PropertyMigration}
 * calls {@link #markDone} after each step succeeds and BEFORE the next one starts, and reads
 * {@link #lastCompletedStep} once at the top of a resume.
 */
public final class MigrationJournal {

    private final Path dir;

    /**
     * @param databaseDir the open database's own directory — the journal lives BESIDE it, one
     *                     level up, never inside it (a database directory is the engine's own to
     *                     manage, and a stray file there is exactly the kind of thing a future
     *                     "clean rebuild" script deletes without knowing what it destroyed).
     */
    public MigrationJournal(Path databaseDir) {
        this.dir = databaseDir.resolveSibling(databaseDir.getFileName() + ".migration-journal");
    }

    private Path fileFor(PropertyRenamePlan plan) {
        String safe = (plan.typeName() + "." + plan.oldName() + "-to-" + plan.newName())
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return dir.resolve(safe + ".journal");
    }

    /** The last step known to have completed for this rename, or 0 if none has. */
    public int lastCompletedStep(PropertyRenamePlan plan) {
        Path f = fileFor(plan);
        if (!Files.exists(f)) return 0;
        for (String line : readLines(f)) {
            if (line.startsWith("step=")) {
                try { return Integer.parseInt(line.substring(5).trim()); }
                catch (NumberFormatException e) { throw corrupt(f, e); }
            }
        }
        return 0;
    }

    /**
     * The index definitions captured before step 3 dropped them — read back on resume so step 6 can
     * rebuild them without the live schema, which by then may no longer carry any trace of them.
     */
    public List<IndexDef> capturedIndexDefs(PropertyRenamePlan plan) {
        Path f = fileFor(plan);
        var out = new ArrayList<IndexDef>();
        if (!Files.exists(f)) return out;
        for (String line : readLines(f)) {
            if (!line.startsWith("index=")) continue;
            String[] parts = line.substring(6).split("\\|", 3);
            if (parts.length != 3) throw corrupt(f, null);
            var props = parts[2].isEmpty() ? List.<String>of() : List.of(parts[2].split(","));
            out.add(new IndexDef(props, Schema.INDEX_TYPE.valueOf(parts[0]), Boolean.parseBoolean(parts[1])));
        }
        return out;
    }

    /** Record step 1's completion together with the index defs step 6 will need once step 3 drops them. */
    public void markDoneWithIndexDefs(PropertyRenamePlan plan, int step, List<IndexDef> defs) {
        var lines = new ArrayList<String>();
        lines.add("step=" + step);
        for (IndexDef d : defs) {
            lines.add("index=" + d.kind() + "|" + d.unique() + "|" + String.join(",", d.properties()));
        }
        write(fileFor(plan), lines);
    }

    /** Record that this step just completed, before the next one starts. Keeps any index defs already filed. */
    public void markDone(PropertyRenamePlan plan, int step) {
        markDoneWithIndexDefs(plan, step, capturedIndexDefs(plan));
    }

    /** The whole recipe finished: nothing left to resume for this rename. */
    public void clear(PropertyRenamePlan plan) {
        try {
            Files.deleteIfExists(fileFor(plan));
        } catch (IOException e) {
            // A stale, now-harmless entry - the recipe itself already completed successfully.
        }
    }

    private static List<String> readLines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw corrupt(f, e);
        }
    }

    private static void write(Path f, List<String> lines) {
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write migration journal " + f, e);
        }
    }

    private static IllegalStateException corrupt(Path f, Exception cause) {
        return new IllegalStateException(
                "Cannot read migration journal " + f + (cause == null ? "" : ": " + cause.getMessage())
                        + ". Resolve this by hand before retrying - resuming blind is exactly what "
                        + "this file exists to prevent.", cause);
    }
}
