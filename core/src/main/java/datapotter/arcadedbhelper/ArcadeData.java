package datapotter.arcadedbhelper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class for ArcadeDB DataHelper code generation.
 *
 * <p>Generates a sealed abstract parent class containing:
 * <ul>
 *   <li>Field constants ($name, $email, etc.)</li>
 *   <li>FIELDS list</li>
 *   <li>Delegating getters/setters</li>
 *   <li>Fluent accessors</li>
 *   <li>DataHelper_I implementation</li>
 *   <li>ArcadeDoc_I implementation</li>
 *   <li>typeDef() schema helper</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * // Document type (default)
 * {@code @ArcadeData}
 * public final class Order extends Order_A {
 *     String orderId;
 *     Double amount;
 * }
 *
 * // Vertex type for graph nodes
 * {@code @ArcadeData(type = ArcadeType.VERTEX)}
 * public final class Person extends Person_A {
 *     String name;
 *     String email;
 *     Integer age;
 *
 *     public static TypeDef{@code <Person>} TYPEDEF =
 *         typeDef().unique($email).__();
 * }
 *
 * // Edge type for graph relationships
 * {@code @ArcadeData(type = ArcadeType.EDGE)}
 * public final class Knows extends Knows_A {
 *     LocalDate since;
 *     String strength;
 * }
 * </pre>
 *
 * <h3>Design Notes:</h3>
 * <ul>
 *   <li><b>Sealed Class:</b> Parent class is sealed and permits only the annotated class</li>
 *   <li><b>Package-Private Fields:</b> Child class fields must be package-private (no modifier) for parent delegation</li>
 *   <li><b>Final Child:</b> Child class should be {@code final} to complete the sealed hierarchy</li>
 *   <li><b>No Lombok Needed:</b> Parent provides all getters/setters via delegation</li>
 *   <li><b>Type Parameter:</b> Controls schema type creation (Document/Vertex/Edge)</li>
 * </ul>
 *
 * <h3>Type Parameter:</h3>
 * <p>The {@code type} parameter determines how the schema is created in ArcadeDB:
 * <ul>
 *   <li>{@link ArcadeType#DOCUMENT} - Standard document type (default)</li>
 *   <li>{@link ArcadeType#VERTEX} - Vertex type for graph nodes</li>
 *   <li>{@link ArcadeType#EDGE} - Edge type for graph relationships</li>
 * </ul>
 *
 * <p><b>Important:</b> The type only affects schema creation. Runtime graph operations
 * (vertex traversal, edge creation, etc.) must use ArcadeDB's native graph API.
 *
 * @see datapotter.datahelper.DataHelper_I
 * @see ArcadeDoc_I
 * @see ArcadeType
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ArcadeData {
    /**
     * The ArcadeDB type for this class (DOCUMENT, VERTEX, or EDGE).
     * Defaults to DOCUMENT.
     *
     * @return the type
     */
    ArcadeType type() default ArcadeType.DOCUMENT;
}
