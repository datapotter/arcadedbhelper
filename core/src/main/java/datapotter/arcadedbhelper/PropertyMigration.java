package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;

import java.util.List;

/**
 * Applies ONE detected property rename (PRP-28 phase 3).
 *
 * <p>Leverages the native {@code Property.rename(String)} primitive ArcadeData/arcadedb#7589 shipped
 * (commit {@code 392ba9d7b1}, milestone 26.10.1) for the schema half of the recipe. It carries EVERY
 * attribute across in one call — type, {@code ofType}, mandatory, notNull, hidden, external,
 * compression, min, max, regexp, default, readonly, and every CUSTOM value including our own identity
 * key — which used to be a hand-maintained list here that silently dropped any custom value other
 * than the id. The rename is schema-metadata-only (the engine's own {@code docs/7589-property-rename.md}
 * spells out why: a record keys each field by a small integer id from a database-wide dictionary also
 * shared by ordinary string values, so the engine cannot cheaply prove repointing it is safe without
 * scanning every record): existing documents keep answering under the OLD name, and only a write made
 * after the rename lands under the new one. So the data movement this recipe exists for is still ours
 * to do — #7589 only makes the schema half free and complete instead of a hand-copied subset.
 * (ArcadeData/arcadedb#7648, open upstream at milestone 26.11.1, proposes the OTHER half of this
 * same problem: an "eager" {@code ALTER PROPERTY .. RENAME} that would have the ENGINE itself
 * rewrite every record from the old field to the new one — exactly the data movement steps 3-4
 * below do by hand today. If it ships, those two steps collapse into one engine statement; until
 * then this class is the only way to migrate the data.)
 *
 * <p><b>The steps, in the only order that is safe:</b>
 * <ol>
 *   <li>Drop every index standing on the OLD property, captured first. {@code Property.rename}
 *       refuses outright while one stands (mirrors {@code dropProperty}'s own refusal), and step 6
 *       needs the shapes captured before they are gone.
 *   <li>{@code old.rename(new)} — the schema swap, via the engine's own primitive. Carries
 *       {@code readonly} across too, which is a trap: a readonly new property refuses the very
 *       {@code UPDATE} the next step needs to run, so it is cleared here and restored in step 5,
 *       never left set across the data copy.
 *   <li>{@code UPDATE <T> SET <new> = <old>} — copies every row's value across, referencing
 *       {@code <old>} as the plain literal field it now is: ArcadeDB's schema is descriptive, not
 *       prescriptive, so a field does not need to be declared to be read or written. Needs an
 *       explicit transaction; DML through {@code db.command} does not open one on its own.
 *   <li>{@code UPDATE <T> REMOVE <old>} — also transactional. <b>Never runs before step 3</b>: doing
 *       so nulls the column, because by then there is nothing left in {@code <old>} to copy.
 *   <li>Restore {@code readonly} on the new property, if the old one had it set.
 *   <li>Recreate the indexes captured in step 1, now naming the NEW property.
 * </ol>
 *
 * <p><b>Resumable by construction.</b> Every step is a no-op if its effect is already visible in the
 * schema, so re-running from a stale {@link MigrationJournal} entry is safe; what makes ordering
 * load-bearing is running steps 3 and 4 in the WRONG order, which this class's fixed sequence makes
 * impossible to do by accident.
 *
 * <p><b>The journal format changed along with the recipe</b> (a step number now means a different
 * action, and it also carries the old {@code readonly} flag). No production database has ever run
 * the six-statement recipe this replaces — see this PRP's own status file — so no live journal needs
 * to survive the change; a stray one from a crash mid-run under the old code should simply be deleted
 * and the rename re-attempted from scratch rather than resumed.
 */
public final class PropertyMigration {

    /** {@code UPDATE ... BATCH n} — avoids committing a large rewrite as one transaction. */
    public static final int DEFAULT_BATCH = 1000;

    private PropertyMigration() {}

    /** Apply one rename, resuming from wherever {@code journal} says this one last got to. */
    public static void applyRename(Database db, PropertyRenamePlan plan, MigrationJournal journal) {
        applyRename(db, plan, journal, DEFAULT_BATCH);
    }

    public static void applyRename(Database db, PropertyRenamePlan plan, MigrationJournal journal, int batchSize) {
        int step = journal.lastCompletedStep(plan);
        DocumentType type = db.getSchema().getType(plan.typeName());
        if (type == null)
            throw new IllegalStateException("Type '" + plan.typeName() + "' does not exist — cannot apply " + plan);

        // Two facts about the OLD property that only exist before step 1 touches anything — captured
        // fresh from the live schema on a first run, read back from the journal on any resume, since
        // by then the rename may already have made the old property's handle gone.
        List<IndexDef> oldIndexDefs = step < 1
                ? Indexes.capture(db, plan.typeName()).stream()
                        .filter(d -> d.properties().contains(plan.oldName()))
                        .toList()
                : journal.capturedIndexDefs(plan);
        boolean wasReadonly = step < 1
                ? type.existsProperty(plan.oldName()) && type.getProperty(plan.oldName()).isReadonly()
                : journal.wasReadonly(plan);

        if (step < 1) {
            dropIndexesOnOld(db, type, oldIndexDefs);
            journal.markDone(plan, 1, oldIndexDefs, wasReadonly);
        }
        if (step < 2) {
            renameViaEngine(type, plan, wasReadonly);
            journal.markDone(plan, 2, oldIndexDefs, wasReadonly);
        }
        if (step < 3) {
            setNewFromOld(db, plan, batchSize);
            journal.markDone(plan, 3, oldIndexDefs, wasReadonly);
        }
        if (step < 4) {
            removeOld(db, plan, batchSize);
            journal.markDone(plan, 4, oldIndexDefs, wasReadonly);
        }
        if (step < 5) {
            restoreReadonly(db, plan, wasReadonly);
            journal.markDone(plan, 5, oldIndexDefs, wasReadonly);
        }
        if (step < 6) {
            rebuildIndexesOnNew(db, plan, oldIndexDefs);
            journal.markDone(plan, 6, oldIndexDefs, wasReadonly);
        }
        journal.clear(plan);
    }

    /**
     * The schema swap, via the engine's own {@code Property.rename}. Every attribute — type,
     * {@code ofType}, mandatory, notNull, hidden, external, compression, min, max, regexp, default,
     * every CUSTOM value including {@link InitDoc#ID_KEY} — carries across in this one call; nothing
     * here needs to know their names any more. {@code readonly} carries across too, and is cleared
     * immediately so steps 3-4 can write into the new property; {@link #restoreReadonly} sets it
     * back once the data copy is done.
     */
    private static void renameViaEngine(DocumentType type, PropertyRenamePlan plan, boolean wasReadonly) {
        if (!type.existsProperty(plan.oldName()) && type.existsProperty(plan.newName())) {
            // resumed after the rename landed but before the journal recorded step 2
            if (wasReadonly) type.getProperty(plan.newName()).setReadonly(false);
            return;
        }
        Property renamed = type.getProperty(plan.oldName()).rename(plan.newName());
        if (wasReadonly) renamed.setReadonly(false);
    }

    private static void restoreReadonly(Database db, PropertyRenamePlan plan, boolean wasReadonly) {
        if (!wasReadonly) return;
        db.getSchema().getType(plan.typeName()).getProperty(plan.newName()).setReadonly(true);
    }

    private static void setNewFromOld(Database db, PropertyRenamePlan plan, int batchSize) {
        String sql = "UPDATE `" + plan.typeName() + "` SET `" + plan.newName() + "` = `" + plan.oldName()
                + "` BATCH " + batchSize;
        db.transaction(() -> db.command("sql", sql));
    }

    private static void dropIndexesOnOld(Database db, DocumentType type, List<IndexDef> oldIndexDefs) {
        Schema schema = db.getSchema();
        for (IndexDef def : oldIndexDefs) {
            var idx = type.getIndexByProperties(def.properties().toArray(String[]::new));
            if (idx != null) schema.dropIndex(idx.getName());
        }
    }

    private static void removeOld(Database db, PropertyRenamePlan plan, int batchSize) {
        String sql = "UPDATE `" + plan.typeName() + "` REMOVE `" + plan.oldName() + "` BATCH " + batchSize;
        db.transaction(() -> db.command("sql", sql));
    }

    private static void rebuildIndexesOnNew(Database db, PropertyRenamePlan plan, List<IndexDef> oldIndexDefs) {
        DocumentType type = db.getSchema().getType(plan.typeName());
        for (IndexDef def : oldIndexDefs) {
            List<String> renamed = def.properties().stream()
                    .map(p -> p.equals(plan.oldName()) ? plan.newName() : p)
                    .toList();
            if (type.getIndexByProperties(renamed.toArray(String[]::new)) != null) continue; // already there
            new IndexDef(renamed, def.kind(), def.unique()).createOn(db, plan.typeName());
        }
    }
}
