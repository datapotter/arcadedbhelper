package datapotter.arcadedbhelper;

import java.util.List;
import datapotter.datahelper.DataHelper_I;

/**
 * Interface representing a type definition for ArcadeDB schema initialization.
 * Defines the structure of a document type including fields and indexes.
 */
public interface TypeDef<E extends DataHelper_I<E>> {

    /**
     * Get the class definition for this type.
     *
     * @return the class that defines this document type
     */
    Class<E> definition();

    /**
     * Get the ArcadeDB type (DOCUMENT, VERTEX, or EDGE).
     *
     * @return the ArcadeType for this schema
     */
    default ArcadeType arcadeType() {
        return ArcadeType.DOCUMENT; // Default for backward compatibility
    }

    /**
     * A zero-reflection factory for this type — the {@code E::new} constructor reference.
     *
     * <p>Declared once, on the type definition, so that reading code never has to repeat it:
     *
     * <pre>
     * public static final TypeDef&lt;DriveFile&gt; TYPEDEF =
     *         schemaBuilder()
     *                 .factory(DriveFile::new)     // &lt;- here, once
     *                 .unique($fileId)
     *                 .__();
     *
     * query(db, DriveFile.TYPEDEF).eq($fileId, id).firstOrNull();   // &lt;- not here, ever
     * </pre>
     *
     * <p>A constructor reference rather than {@code definition().newInstance()} on purpose: reflective
     * instantiation would need GraalVM-native configuration and is unavailable on TeaVM, which is the
     * whole reason DataHelper avoids reflection everywhere else.
     *
     * <p>Defaults to {@code null} so that a {@code TypeDef} written before this existed still compiles
     * and still satisfies the interface. Callers that need it say so with a clear error rather than a
     * {@code NullPointerException} — see {@code Query}.
     *
     * @return the factory, or {@code null} if this type definition does not declare one
     */
    default java.util.function.Supplier<E> factory() {
        return null;
    }

    /**
     * Get the list of field names to be created in the schema.
     *
     * @return list of field names
     */
    List<String> fields();

    /**
     * Get the list of LINK fields (schema-only, not in regular fields list).
     * These will be registered with Type.LINK in the schema.
     *
     * @return list of LINK field objects
     */
    default List<datapotter.datahelper.Field_I<E, ?>> linkFields() {
        return List.of(); // Default: no LINK fields
    }

    /**
     * Get full-text search indexes to create.
     *
     * @return list of field name arrays for full-text indexes
     */
    List<String[]> fullStringIndexes();

    /**
     * Get LSM tree indexes to create.
     *
     * @return list of field name arrays for LSM tree indexes
     */
    List<String[]> lsmIndexes();

    /**
     * Get unique indexes to create.
     *
     * @return list of field name arrays for unique indexes
     */
    List<String[]> uniqueIndexes();
}
