package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of a <b>keyed map of references</b> (n:n) to {@code @ArcadeData} records.
 *
 * <p>The map-of-links analogue of {@link Link}: each value is a {@link Link}, individually either
 * identity-only or projected, exactly as the query returned it. No value ever performs hidden I/O;
 * {@link #resolveAll(Database)} is the explicit batch fetch.
 *
 * @param <K> the key type
 * @param <T> the referenced (value) entity type
 */
public final class LinkMap<K, T extends ArcadeDoc_I<T>> {

    private final Map<K, Link<T>> links;

    public LinkMap(Map<K, Link<T>> links) {
        this.links = links == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(links));
    }

    public static <K, T extends ArcadeDoc_I<T>> LinkMap<K, T> of(Map<K, Link<T>> links) {
        return new LinkMap<>(links);
    }

    /** The underlying key→link map (unmodifiable). */
    public Map<K, Link<T>> links() { return links; }

    public int size() { return links.size(); }
    public boolean isEmpty() { return links.isEmpty(); }
    public Set<K> keys() { return links.keySet(); }
    public Link<T> get(K key) { return links.get(key); }

    /** key→RID for every entry. */
    public Map<K, String> rids() {
        var m = new LinkedHashMap<K, String>();
        links.forEach((k, v) -> m.put(k, v.rid()));
        return m;
    }

    /** Explicitly fetch every target from the database by RID. The batch I/O entry point. */
    public Map<K, T> resolveAll(Database db) {
        var m = new LinkedHashMap<K, T>();
        links.forEach((k, v) -> m.put(k, v.resolve(db)));
        return m;
    }

    @Override
    public String toString() { return "LinkMap" + rids(); }
}
