package datapotter.arcadedbhelper;

/**
 * One property rename detected by identity, matched by a stable id under a different name, and NOT
 * yet applied (PRP-28 phase 3). ArcadeDB has no property-rename primitive — applying one rewrites
 * every row of the type — so detection (free, at schema init) and application (a backup, a plan
 * reviewed, an explicit flag, a journalled multi-step recipe) are deliberately two different things.
 *
 * @param typeName the ArcadeDB type this property belongs to
 * @param oldName  the property's CURRENT name in the schema
 * @param newName  the name the declared field now carries
 * @param id       the stable id ({@code @P}) that matched the two
 * @see MigrationPlan
 * @see PropertyMigration
 */
public record PropertyRenamePlan(String typeName, String oldName, String newName, String id) {
    @Override
    public String toString() {
        return typeName + "." + oldName + " -> " + newName + "  (id " + id + ")";
    }
}
