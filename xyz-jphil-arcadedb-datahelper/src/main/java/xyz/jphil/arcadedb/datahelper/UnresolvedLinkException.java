package xyz.jphil.arcadedb.datahelper;

/**
 * Thrown by {@link Link#get()} when the link target was not projected by the query and is
 * therefore not present in the snapshot. This is intentional: a {@code @ArcadeData} object is a
 * detached snapshot, so reading an unresolved reference must not silently trigger I/O. Either
 * project the target in the query (e.g. {@code SELECT *, customer:{...}}) or resolve it explicitly
 * with {@link Link#resolve(com.arcadedb.database.Database)}.
 */
public final class UnresolvedLinkException extends RuntimeException {
    public UnresolvedLinkException(String rid) {
        super("Link " + (rid == null ? "(unset)" : rid) + " is not resolved: the target was not "
            + "projected by the query. Project it (e.g. SELECT *, field:{...}) or call resolve(db).");
    }
}
