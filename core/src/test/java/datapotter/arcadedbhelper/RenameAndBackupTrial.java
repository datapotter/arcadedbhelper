package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Trial for {@link Rename} and {@link Backups} — the two wrappers that make the ArcadeDB version
 * choice stop mattering.
 *
 * <p><b>Every assertion here is about a UNIQUE CONSTRAINT, not about an index being present.</b> That is
 * the whole point: in the broken case the index looks fine in memory and fine right after the rename,
 * and only a duplicate insert AFTER A REOPEN shows that the constraint is gone. A test that asserts
 * presence passes while the data quietly corrupts.
 *
 * <p>Run: {@code mvn exec:java -Dexec.mainClass="datapotter.arcadedbhelper.RenameAndBackupTrial" -Dexec.classpathScope=test}
 */
public class RenameAndBackupTrial {

    static int failed = 0;

    public static void main(String[] args) throws Exception {
        var tmp = Path.of(System.getProperty("java.io.tmpdir"));
        var dbDir = tmp.resolve("datapotter-rename-backup-trial");
        var archive = tmp.resolve("datapotter-trial-backup.zip");
        var restoredDir = tmp.resolve("datapotter-rename-backup-trial-restored");
        rmrf(dbDir); rmrf(restoredDir); Files.deleteIfExists(archive);

        System.out.println("=== Rename + Backups trial ===\n");

        var db = new DatabaseFactory(dbDir.toString()).create();
        try {
            seed(db, "Person", 5);
            check("index exists before rename", db.getSchema().getType("Person").getIndexByProperties("email") != null);

            var rebuilt = Rename.type(db, "Person", "Human");
            check("type renamed", db.getSchema().existsType("Human") && !db.getSchema().existsType("Person"));
            check("rows survived", count(db, "Human") == 5);
            System.out.println("     rebuilt: " + rebuilt);

            Backups.take(db, archive);
            check("archive written to the asked-for path", Files.exists(archive));
            check("archive has no nested entries", Backups.nestedEntries(archive).isEmpty());
        } finally { db.close(); }

        // The reopen is where a lost index shows up - everything above passes even when it is broken.
        var reopened = new DatabaseFactory(dbDir.toString()).open();
        try {
            check("index present after reopen", reopened.getSchema().getType("Human").getIndexByProperties("email") != null);
            check("UNIQUENESS STILL ENFORCED after reopen", duplicateRefused(reopened, "Human", "e0"));
        } finally { reopened.close(); }

        Backups.extract(archive, restoredDir);
        var restored = new DatabaseFactory(restoredDir.toString()).open();
        try {
            check("restored archive opens", true);
            check("restored rows intact", count(restored, "Human") == 5);
            check("UNIQUENESS ENFORCED in the restored copy", duplicateRefused(restored, "Human", "e0"));
        } finally { restored.close(); }

        System.out.println(failed == 0 ? "\n=== all checks passed ===" : "\n=== " + failed + " CHECK(S) FAILED ===");
        if (failed > 0) System.exit(1);
    }

    static void seed(Database db, String typeName, int rows) {
        var t = db.getSchema().createDocumentType(typeName);
        t.createProperty("email", Type.STRING);
        t.createProperty("name", Type.STRING);
        db.transaction(() -> {
            for (var i = 0; i < rows; i++) db.newDocument(typeName).set("email", "e" + i).set("name", "n" + i).save();
        });
        db.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, typeName, new String[]{"email"});
    }

    /** The only check that catches a silently dropped unique index. */
    static boolean duplicateRefused(Database db, String typeName, String existingEmail) {
        try {
            db.transaction(() -> db.newDocument(typeName).set("email", existingEmail).save());
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    static long count(Database db, String typeName) {
        try (var rs = db.query("sql", "SELECT count(*) as c FROM " + typeName)) {
            return ((Number) rs.next().getProperty("c")).longValue();
        }
    }

    static void check(String what, boolean ok) {
        if (!ok) failed++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
    }

    static void rmrf(Path p) {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) {
            w.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
