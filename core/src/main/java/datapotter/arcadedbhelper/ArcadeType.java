package datapotter.arcadedbhelper;

/**
 * Enum representing the ArcadeDB type for a class annotated with @ArcadeData.
 *
 * <p>This determines how the schema is created in ArcadeDB:
 * <ul>
 *   <li>{@link #DOCUMENT} - Standard document type (default)</li>
 *   <li>{@link #VERTEX} - Graph vertex type for nodes in a property graph</li>
 *   <li>{@link #EDGE} - Graph edge type for relationships between vertices</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * {@code @ArcadeData(type = ArcadeType.VERTEX)}
 * public final class Person extends Person_A {
 *     String name;
 *     String email;
 * }
 *
 * {@code @ArcadeData(type = ArcadeType.EDGE)}
 * public final class Knows extends Knows_A {
 *     LocalDate since;
 * }
 *
 * {@code @ArcadeData}  // Defaults to DOCUMENT
 * public final class Order extends Order_A {
 *     String orderId;
 * }
 * </pre>
 *
 * <p><b>Important:</b> The type only affects schema creation. Runtime graph operations
 * (traversal, edge creation, etc.) must still use ArcadeDB's native graph API.
 * The @ArcadeData processor generates field constants and CRUD helpers, but does not
 * expose graph-specific internals like RID, @in, @out, etc.
 *
 * @see ArcadeData
 */
public enum ArcadeType {
    /**
     * Standard document type (default).
     * Creates a DocumentType in the schema using {@code createDocumentType()}.
     *
     * <p>Use for regular data entities that don't participate in graph traversal.
     */
    DOCUMENT,

    /**
     * Vertex (node) type for property graphs.
     * Creates a VertexType in the schema using {@code createVertexType()}.
     *
     * <p>Use for entities that will be nodes in a graph with edges connecting them.
     * At runtime, use ArcadeDB's {@code db.newVertex()} and vertex traversal APIs.
     */
    VERTEX,

    /**
     * Edge (relationship) type for property graphs.
     * Creates an EdgeType in the schema using {@code createEdgeType()}.
     *
     * <p>Use to model relationship properties (e.g., "since" date on a "Knows" edge).
     * At runtime, use ArcadeDB's {@code vertex.newEdge()} API to create edges.
     * The @in and @out properties are managed by ArcadeDB, not exposed in Java.
     */
    EDGE
}
