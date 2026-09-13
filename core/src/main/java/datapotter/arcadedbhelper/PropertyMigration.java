package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;

import java.util.List;

/**
 * Applies ONE detected property rename (PRP-28 phase 3), over the seven-statement recipe measured
 * against a real engine in {@code 28-prp.05}/{@code .06} — set-based, not a document walk, because
 * ArcadeDB documents carry their own field names and there is no {@code ALTER PROPERTY .. NAME}.
 *
 * <p><b>The steps, in the only order that is safe:</b>
 * <ol>
 *   <li>Create the new property (Java API — sidesteps the inline {@code CREATE PROPERTY .. CUSTOM}
 *       parser bug live on 26.7.3), carrying the old property's type, {@code ofType} and constraints,
 *       and its stable id.
 *   <li>{@code UPDATE <T> SET <new> = <old>} — copies every row's value across. Needs an explicit
 *       transaction; DML through {@code db.command} does not open one on its own.
 *   <li>Drop every index standing on the OLD property. Must happen before the next step:
 *       {@code DROP PROPERTY} refuses while an index stands on the property it is dropping.
 *   <li>{@code UPDATE <T> REMOVE <old>} — also transactional. <b>Never runs before step 2</b>: doing
 *       so nulls the column, because by then there is nothing left in {@code <old>} to copy.
 *   <li>Drop the old property (Java API).
 *   <li>Recreate the indexes captured in step 3, now naming the NEW property.
 * </ol>
 *
 * <p><b>Resumable by construction.</b> Every step is a no-op if its effect is already visible in the
 * schema, so re-running from a stale {@link MigrationJournal} entry is safe; what makes ordering
 * load-bearing is running steps 2 and 4 in the WRONG order, which this class's fixed sequence makes
 * impossible to do by accident.
 *
 * <p><b>Known gap, deliberately not built:</b> {@code readonly} is not carried onto the new property
 * (a readonly property would refuse the very UPDATE this recipe needs to run), and BATCH is a fixed
 * default rather than a per-call tuning knob. Neither is exercised by anything this library ships
 * against today.
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

        // The index defs standing on the OLD property. Captured fresh from the live schema while it
        // still exists (true up to and including step 2); once step 3 has dropped them the live
        // schema can no longer answer this, so a resume past that point reads them back from the
        // journal, which is exactly why they are written there the moment they are captured.
        List<IndexDef> oldIndexDefs = step < 3
                ? Indexes.capture(db, plan.typeName()).stream()
                        .filter(d -> d.properties().contains(plan.oldName()))
                        .toList()
                : journal.capturedIndexDefs(plan);

        if (step < 1) {
            createNewProperty(type, plan);
            journal.markDoneWithIndexDefs(plan, 1, oldIndexDefs);
        }
        if (step < 2) {
            setNewFromOld(db, plan, batchSize);
            journal.markDoneWithIndexDefs(plan, 2, oldIndexDefs);
        }
        if (step < 3) {
            dropIndexesOnOld(db, type, oldIndexDefs);
            journal.markDoneWithIndexDefs(plan, 3, oldIndexDefs);
        }
        if (step < 4) {
            removeOld(db, plan, batchSize);
            journal.markDoneWithIndexDefs(plan, 4, oldIndexDefs);
        }
        if (step < 5) {
            dropOldProperty(type, plan);
            journal.markDoneWithIndexDefs(plan, 5, oldIndexDefs);
        }
        if (step < 6) {
            rebuildIndexesOnNew(db, plan, oldIndexDefs);
            journal.markDoneWithIndexDefs(plan, 6, oldIndexDefs);
        }
        journal.clear(plan);
    }

    private static void createNewProperty(DocumentType type, PropertyRenamePlan plan) {
        if (type.existsProperty(plan.newName())) return; // resumed after a partial create
        Property old = type.getProperty(plan.oldName());
        Property created = type.createProperty(plan.newName(), old.getType());
        if (old.getOfType() != null) created.setOfType(old.getOfType());
        created.setMandatory(old.isMandatory());
        created.setNotNull(old.isNotNull());
        created.setHidden(old.isHidden());
        created.setExternal(old.isExternal());
        if (old.getMin() != null) created.setMin(old.getMin());
        if (old.getMax() != null) created.setMax(old.getMax());
        if (old.getRegexp() != null) created.setRegexp(old.getRegexp());
        if (old.getDefaultValue() != null) created.setDefaultValue(old.getDefaultValue());
        if (old.getCompression() != null) created.setCompression(old.getCompression());
        created.setCustomValue(InitDoc.ID_KEY, plan.id());
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

    private static void dropOldProperty(DocumentType type, PropertyRenamePlan plan) {
        if (!type.existsProperty(plan.oldName())) return; // already dropped, resumed run
        type.dropProperty(plan.oldName());
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
