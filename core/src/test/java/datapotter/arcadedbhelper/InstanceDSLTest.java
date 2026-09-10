package datapotter.arcadedbhelper;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.server.ArcadeDBServer;

import java.nio.file.Files;
import java.nio.file.Path;

import static datapotter.arcadedbhelper.TestPersonDTO_I.*;

/**
 * Test demonstrating the new instance-level DSL for ArcadeDB operations.
 *
 * This shows the cleaner pattern where upsert/insert are terminal operations.
 */
public class InstanceDSLTest {

    private static final String DB_NAME = "test_instance_dsl_db";
    private static ArcadeDBServer server;
    private static Path dbPath;

    public static void main(String[] args) {
        try {
            System.out.println("=== Instance-Level DSL Test ===\n");

            // Initialize database
            initDatabase();

            // Test 1: Simple insert with new DSL
            testSimpleInsert();

            // Test 2: Upsert with whereEq
            testUpsertWithWhere();

            // Test 3: Selective field update
            testSelectiveFieldUpdate();

            // Test 4: Compare old vs new syntax
            testBothSyntaxPatterns();

            System.out.println("\n=== All DSL tests completed successfully! ===");

        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private static void initDatabase() {
        try {
            System.out.println("1. Initializing ArcadeDB...");

            // Create temp directory
            dbPath = Files.createTempDirectory("arcadedb_dsl_test_");
            System.out.println("   Database path: " + dbPath);

            // Configure ArcadeDB
            GlobalConfiguration.SERVER_ROOT_PATH.setValue(dbPath.toString());
            GlobalConfiguration.PROFILE.setValue("low-ram");
            GlobalConfiguration.SERVER_ROOT_PASSWORD.setValue("admin12345");
            GlobalConfiguration.SERVER_METRICS.setValue(false);

            // Start server
            server = new ArcadeDBServer(new ContextConfiguration());
            server.start();

            // Create database
            if (!server.existsDatabase(DB_NAME)) {
                server.createDatabase(DB_NAME, com.arcadedb.engine.ComponentFile.MODE.READ_WRITE);
            }

            System.out.println("   ✓ Database initialized\n");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private static void testSimpleInsert() {
        System.out.println("2. Testing simple insert with new DSL...");

        Database db = server.getDatabase(DB_NAME);

        db.transaction(() -> {
            // Initialize schema
            InitDoc.initDocTypes(db, TestPersonDTO.TYPEDEF);

            // Create person
            TestPersonDTO person = new TestPersonDTO();
            person.setName("Alice");
            person.setEmail("alice@example.com");
            person.setAge(25);

            // NEW DSL: person.in(db).insert()
            Document doc = person.in(db).insert();

            System.out.println("   ✓ Inserted: " + doc.get("name") + " (" + doc.get("email") + ")");
            System.out.println("   Document @rid: " + doc.getIdentity());
        });

        System.out.println();
    }

    private static void testUpsertWithWhere() {
        System.out.println("3. Testing upsert with whereEq...");

        Database db = server.getDatabase(DB_NAME);

        db.transaction(() -> {
            TestPersonDTO person = new TestPersonDTO();
            person.setName("Bob");
            person.setEmail("bob@example.com");
            person.setAge(30);

            // NEW DSL: Upsert with where condition
            Document doc = person.in(db)
                    .whereEq($email, person.getEmail())
                    .upsert();

            System.out.println("   ✓ First upsert: " + doc.get("name") + ", age=" + doc.get("age"));

            // Update age
            person.setAge(31);
            Document doc2 = person.in(db)
                    .whereEq($email, person.getEmail())
                    .upsert();

            System.out.println("   ✓ Second upsert: " + doc2.get("name") + ", age=" + doc2.get("age"));
            System.out.println("   Same document? " + doc.getIdentity().equals(doc2.getIdentity()));
        });

        System.out.println();
    }

    private static void testSelectiveFieldUpdate() {
        System.out.println("4. Testing selective field update...");

        Database db = server.getDatabase(DB_NAME);

        db.transaction(() -> {
            TestPersonDTO person = new TestPersonDTO();
            person.setName("Charlie");
            person.setEmail("charlie@example.com");
            person.setAge(35);

            // Insert first
            person.in(db).insert();

            // Update only age field using selective from()
            person.setName("Charlie Updated");  // This won't be saved
            person.setAge(36);                   // This will be saved

            Document doc = person.in(db)
                    .whereEq($email, person.getEmail())
                    .fields($age)  // Only update age (type-safe)
                    .upsert();

            System.out.println("   ✓ After selective update:");
            System.out.println("     Name (unchanged): " + doc.get("name"));  // Should be "Charlie"
            System.out.println("     Age (updated): " + doc.get("age"));      // Should be 36
        });

        System.out.println();
    }

    private static void testBothSyntaxPatterns() {
        System.out.println("5. Comparing old vs new syntax...");

        Database db = server.getDatabase(DB_NAME);

        db.transaction(() -> {
            TestPersonDTO person = new TestPersonDTO();
            person.setName("Dave");
            person.setEmail("dave@example.com");
            person.setAge(40);

            System.out.println("   OLD SYNTAX (still works):");
            System.out.println("   Update.use(db)");
            System.out.println("       .select(\"TestPersonDTO\")");
            System.out.println("       .whereEq(\"email\", person.getEmail())");
            System.out.println("       .upsert()");
            System.out.println("       .from(person)");
            System.out.println("       .saveDocument();");

            Document doc1 = Update.use(db)
                    .select("TestPersonDTO")
                    .whereEq("email", person.getEmail())  // Old API uses strings
                    .upsert()
                    .from(person, new String[0])  // Explicit empty array to avoid ambiguity
                    .saveDocument();

            System.out.println("   ✓ Saved via old syntax: " + doc1.get("name"));

            System.out.println("\n   NEW SYNTAX (cleaner):");
            System.out.println("   person.in(db)");
            System.out.println("       .whereEq(\"email\", person.getEmail())");
            System.out.println("       .upsert();");

            person.setAge(41);
            Document doc2 = person.in(db)
                    .whereEq($email, person.getEmail())
                    .upsert();

            System.out.println("   ✓ Updated via new syntax: age=" + doc2.get("age"));
            System.out.println("   Same document? " + doc1.getIdentity().equals(doc2.getIdentity()));
        });

        System.out.println();
    }

    private static void cleanup() {
        try {
            System.out.println("6. Shutting down...");
            if (server != null) {
                server.stop();
                System.out.println("   ✓ Server stopped");
            }
            if (dbPath != null && Files.exists(dbPath)) {
                deleteDirectory(dbPath);
                System.out.println("   ✓ Cleaned up test directory");
            }
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
        }
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteDirectory(p);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
