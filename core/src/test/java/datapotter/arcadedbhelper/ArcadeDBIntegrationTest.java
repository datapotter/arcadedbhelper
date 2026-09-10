package datapotter.arcadedbhelper;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.server.ArcadeDBServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static datapotter.arcadedbhelper.TestPersonDTO_I.*;

/**
 * Integration test demonstrating ArcadeDB DataHelper usage.
 * This follows the patterns from arcade-db-working-examples.md
 */
public class ArcadeDBIntegrationTest {

    private static final String DB_NAME = "test_datahelper_db";
    private static ArcadeDBServer server;
    private static Path dbPath;

    public static void main(String[] args) {
        try {
            System.out.println("=== ArcadeDB DataHelper Integration Test ===\n");

            // Initialize database
            initDatabase();

            // Test 1: Schema initialization
            testSchemaInitialization();

            // Test 2: Create and save document
            testCreateAndSave();

            // Test 3: Query and load document
            testQueryAndLoad();

            // Test 4: Update document
            testUpdate();

            // Test 5: Selective field mapping
            testSelectiveFieldMapping();

            System.out.println("\n=== All tests completed successfully! ===");

        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private static void initDatabase() throws Exception {
        System.out.println("1. Initializing ArcadeDB...");

        // Create temp directory for test database
        dbPath = Files.createTempDirectory("arcadedb_test_");
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
            server.createDatabase(DB_NAME, ComponentFile.MODE.READ_WRITE);
        }

        System.out.println("   ✓ Database initialized\n");
    }

    private static void testSchemaInitialization() {
        System.out.println("2. Testing schema initialization...");

        Database db = server.getDatabase(DB_NAME);

        // Initialize schema using TypeDef
        InitDoc.initDocTypes(db, TestPersonDTO.TYPEDEF);

        System.out.println("   ✓ Schema initialized with unique index on 'email'\n");
    }

    private static void testCreateAndSave() {
        System.out.println("3. Testing create and save...");

        Database db = server.getDatabase(DB_NAME);

        // Modern transaction pattern
        db.transaction(() -> {
            // Create a person
            TestPersonDTO person = new TestPersonDTO();
            person.name("John Doe");
            person.email("john@example.com");
            person.age(30);

            // Save directly - no intermediate Map conversion needed!
            Update.use(db)
                    .select("TestPersonDTO")
                    .whereEq("email", person.email())  // Old API uses strings
                    .upsert()
                    .from(person, new String[0])  // Explicit empty array to avoid ambiguity
                    .saveDocument();

            System.out.println("   ✓ Created person: " + person.name() + " (" + person.email() + ")");
        });

        System.out.println();
    }

    private static void testQueryAndLoad() {
        System.out.println("4. Testing query and load...");

        Database db = server.getDatabase(DB_NAME);

        // Query using native Java DSL
        var docs = db.select()
                .fromType("TestPersonDTO")
                .where().property("email").eq().value("john@example.com")
                .documents();

        if (docs.hasNext()) {
            var doc = docs.next();

            // Load into DataHelper object
            TestPersonDTO person = new TestPersonDTO();
            person.fromArcadeDocument(doc);

            System.out.println("   ✓ Found person: " + person.name());
            System.out.println("     Email: " + person.email());
            System.out.println("     Age: " + person.age());
        } else {
            throw new RuntimeException("Person not found!");
        }

        System.out.println();
    }

    private static void testUpdate() {
        System.out.println("5. Testing update...");

        Database db = server.getDatabase(DB_NAME);

        // Modern transaction with update
        db.transaction(() -> {
            Update.use(db)
                    .select("TestPersonDTO")
                    .whereEq("email", "john@example.com")  // Old API uses strings
                    .upsert(alreadyExists -> {
                        System.out.println("   Document exists: " + alreadyExists);
                    })
                    .__("age", () -> 31)
                    .__("name", () -> "John Updated Doe")
                    .saveDocument();

            System.out.println("   ✓ Updated person's age and name");
        });

        // Verify update
        var docs = db.select()
                .fromType("TestPersonDTO")
                .where().property("email").eq().value("john@example.com")
                .documents();

        if (docs.hasNext()) {
            TestPersonDTO person = new TestPersonDTO();
            person.fromArcadeDocument(docs.next());
            System.out.println("   ✓ Verified: " + person.name() + ", age " + person.age());
        }

        System.out.println();
    }

    private static void testSelectiveFieldMapping() {
        System.out.println("6. Testing selective field mapping...");

        Database db = server.getDatabase(DB_NAME);

        // Create a new person with different email
        db.transaction(() -> {
            TestPersonDTO person = new TestPersonDTO();
            person.name("Jane Doe");
            person.email("jane@example.com");
            person.age(25);

            Update.use(db)
                    .select("TestPersonDTO")
                    .whereEq("email", person.email())  // Old API uses strings
                    .upsert()
                    .from(person, new String[0])  // Explicit empty array to avoid ambiguity
                    .saveDocument();

            System.out.println("   ✓ Created Jane with age 25");
        });

        // Update only the age field, not name
        db.transaction(() -> {
            TestPersonDTO update = new TestPersonDTO();
            update.name("Should Not Be Saved");
            update.age(26);

            Update.use(db)
                    .select("TestPersonDTO")
                    .whereEq("email", "jane@example.com")  // Old API uses strings
                    .upsert()
                    .from(update, "age")  // Only update age field
                    .saveDocument();

            System.out.println("   ✓ Updated only age field");
        });

        // Verify name is unchanged, age is updated
        var docs = db.select()
                .fromType("TestPersonDTO")
                .where().property("email").eq().value("jane@example.com")
                .documents();

        if (docs.hasNext()) {
            TestPersonDTO person = new TestPersonDTO();
            person.fromArcadeDocument(docs.next());

            if ("Jane Doe".equals(person.name()) && Integer.valueOf(26).equals(person.age())) {
                System.out.println("   ✓ Verified: Name unchanged ('" + person.name() + "'), age updated to " + person.age());
            } else {
                throw new RuntimeException("Selective update failed! Name: " + person.name() + ", Age: " + person.age());
            }
        }

        System.out.println();
    }

    private static void shutdown() {
        System.out.println("7. Shutting down...");
        if (server != null && server.isStarted()) {
            try {
                server.stop();
                System.out.println("   ✓ Server stopped");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Cleanup temp directory
        try {
            if (dbPath != null && Files.exists(dbPath)) {
                deleteDirectory(dbPath);
                System.out.println("   ✓ Cleaned up test directory");
            }
        } catch (Exception e) {
            System.err.println("   Warning: Could not cleanup test directory: " + e.getMessage());
        }
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                entries.forEach(entry -> {
                    try {
                        deleteDirectory(entry);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
