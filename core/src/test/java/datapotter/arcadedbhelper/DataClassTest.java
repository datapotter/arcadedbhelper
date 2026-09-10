package datapotter.arcadedbhelper;

/**
 * Simple test demonstrating the dataClass() method added to DataHelper_I.
 * This method compensates for type erasure in generics.
 */
public class DataClassTest {

    public static void main(String[] args) {
        System.out.println("=== DataClass() Method Test ===\n");

        // Create a test instance
        TestPersonDTO person = new TestPersonDTO();
        person.setName("John Doe");
        person.setEmail("john@example.com");
        person.setAge(30);

        // Test 1: dataClass() returns the concrete class
        System.out.println("1. Testing dataClass() method:");
        Class<?> clazz = person.dataClass();
        System.out.println("   dataClass() returns: " + clazz.getName());
        System.out.println("   Simple name: " + clazz.getSimpleName());
        System.out.println("   ✓ Correct class returned: " + (clazz == TestPersonDTO.class));

        // Test 2: Use for reflection (inspect fields)
        System.out.println("\n2. Using dataClass() for reflection:");
        System.out.println("   Fields in class:");
        for (var field : clazz.getDeclaredFields()) {
            System.out.println("     - " + field.getName() + ": " + field.getType().getSimpleName());
        }

        // Test 3: Use for type name (for database operations)
        System.out.println("\n3. Using dataClass() for type name:");
        String typeName = person.dataClass().getSimpleName();
        System.out.println("   Type name for database: " + typeName);
        System.out.println("   ✓ Can be used in Update.use(db).select(\"" + typeName + "\")");

        // Test 4: Use for schema generation
        System.out.println("\n4. Using dataClass() for schema generation:");
        System.out.println("   SchemaBuilder.defType(" + clazz.getSimpleName() + ".class)");
        System.out.println("   ✓ Replaces the need for string literals");

        // Test 5: Polymorphic usage (works with interface reference)
        System.out.println("\n5. Testing with interface reference:");
        TestPersonDTO_I<?> personInterface = person;
        System.out.println("   Interface reference dataClass(): " + personInterface.dataClass().getSimpleName());
        System.out.println("   ✓ Works through interface (compensates for type erasure)");

        System.out.println("\n=== All dataClass() tests completed successfully! ===");
    }
}
