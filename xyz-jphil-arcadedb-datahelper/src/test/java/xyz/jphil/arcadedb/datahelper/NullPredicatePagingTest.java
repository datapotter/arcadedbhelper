package xyz.jphil.arcadedb.datahelper;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.server.ArcadeDBServer;

import java.nio.file.Files;

import static xyz.jphil.arcadedb.datahelper.Query.query;
import static xyz.jphil.arcadedb.datahelper.TestPersonDTO_I.$age;
import static xyz.jphil.arcadedb.datahelper.TestPersonDTO_I.$name;
import static xyz.jphil.arcadedb.datahelper.Upsert.upsert;

/**
 * The engine's native executor spends a limit on candidates before evaluating IS NULL / IS NOT NULL,
 * so such a query returns a fraction of the matches with no error. {@link Query} refuses that shape
 * rather than passing the wrong answer up. See prp/26.
 *
 * <p>Run: {@code mvn exec:java -Dexec.mainClass="xyz.jphil.arcadedb.datahelper.NullPredicatePagingTest"
 * -Dexec.classpathScope=test}
 */
public class NullPredicatePagingTest {

    private static final String DB_NAME = "test_nullpredicate_db";
    private static final int ROWS = 100, MATCHES = 20;

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        var dbPath = Files.createTempDirectory("arcadedb_nullpred_");
        GlobalConfiguration.SERVER_ROOT_PATH.setValue(dbPath.toString());
        GlobalConfiguration.PROFILE.setValue("low-ram");
        GlobalConfiguration.SERVER_ROOT_PASSWORD.setValue("admin12345");
        GlobalConfiguration.SERVER_METRICS.setValue(false);

        var server = new ArcadeDBServer(new ContextConfiguration());
        server.start();
        try {
            if (!server.existsDatabase(DB_NAME)) server.createDatabase(DB_NAME, ComponentFile.MODE.READ_WRITE);
            var db = server.getDatabase(DB_NAME);
            InitDoc.initDocTypes(db, TestPersonDTO.TYPEDEF);
            seed(db);

            refused("null check + another condition + limit",
                    () -> query(db, TestPersonDTO.TYPEDEF, TestPersonDTO::new).eq($name, "x").isNotNull($age).limit(50).list());
            refused("null check + another condition + skip",
                    () -> query(db, TestPersonDTO.TYPEDEF, TestPersonDTO::new).eq($name, "x").isNotNull($age).skip(10).list());
            refused("isNull is refused on the same terms",
                    () -> query(db, TestPersonDTO.TYPEDEF, TestPersonDTO::new).eq($name, "x").isNull($age).limit(50).list());

            allowed("null check alone with a limit — the scan is driven by that field's own index",
                    () -> query(db, TestPersonDTO.TYPEDEF, TestPersonDTO::new).isNotNull($age).limit(50).list().size());
            allowed("null check with another condition but no paging",
                    () -> query(db, TestPersonDTO.TYPEDEF, TestPersonDTO::new).eq($name, "x").isNotNull($age).list().size());
            allowed("ordinary conditions still page normally",
                    () -> query(db, TestPersonDTO.TYPEDEF, TestPersonDTO::new).eq($name, "x").limit(50).list().size());

            System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        } finally {
            server.stop();
        }
        if (failures > 0) System.exit(1);
    }

    private static void seed(Database db) {
        db.transaction(() -> {
            for (var i = 0; i < ROWS; i++) {
                var u = upsert(db, TestPersonDTO.TYPEDEF)
                        .key(TestPersonDTO_I.$email, "p" + i + "@example.com")
                        .set($name, "x");
                if (i % (ROWS / MATCHES) == 0) u.set($age, 30 + i);
                u.save();
            }
        });
    }

    private static void refused(String what, Runnable r) {
        try {
            r.run();
            fail(what + " — expected a refusal, got a result");
        } catch (IllegalArgumentException e) {
            // Insist it is OUR refusal: an unrelated IllegalArgumentException (a missing factory, say)
            // would otherwise let this test pass while proving nothing.
            var msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("limit/skip")) pass(what + " — refused: " + firstLine(msg));
            else fail(what + " — refused, but for an unrelated reason: " + firstLine(msg));
        } catch (RuntimeException e) {
            fail(what + " — expected IllegalArgumentException, got " + e);
        }
    }

    private static void allowed(String what, java.util.function.Supplier<Integer> r) {
        try {
            pass(what + " — allowed, " + r.get() + " rows");
        } catch (RuntimeException e) {
            fail(what + " — should be allowed, but threw " + e);
        }
    }

    private static String firstLine(String s) {
        var cut = s == null ? "" : s;
        return cut.length() <= 90 ? cut : cut.substring(0, 90) + "…";
    }

    private static void pass(String msg) {
        System.out.println("  ok   " + msg);
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("  FAIL " + msg);
    }
}
