package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a <b>list of references</b> (1:n / n:n) to {@code @ArcadeData} records.
 *
 * <p>The list-of-links analogue of {@link Link}: each element is a {@link Link}, individually either
 * identity-only or projected, exactly as the query returned it. No element ever performs hidden I/O;
 * {@link #resolveAll(Database)} is the explicit batch fetch.
 *
 * @param <T> the referenced (element) entity type
 */
public final class LinkList<T extends ArcadeDoc_I<T>> {

    private final List<Link<T>> links;

    public LinkList(List<Link<T>> links) {
        this.links = links == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(links));
    }

    public static <T extends ArcadeDoc_I<T>> LinkList<T> of(List<Link<T>> links) {
        return new LinkList<>(links);
    }

    /** The underlying links (unmodifiable). */
    public List<Link<T>> links() { return links; }

    public int size() { return links.size(); }
    public boolean isEmpty() { return links.isEmpty(); }
    public Link<T> get(int index) { return links.get(index); }

    /** The RIDs of every link, in order. */
    public List<String> rids() { return links.stream().map(Link::rid).toList(); }

    /** The projected targets (throws via {@link Link#get()} if any element is unresolved). Never I/O. */
    public List<T> getAll() { return links.stream().map(Link::get).toList(); }

    /** Explicitly fetch every target from the database by RID. The batch I/O entry point. */
    public List<T> resolveAll(Database db) { return links.stream().map(l -> l.resolve(db)).toList(); }

    @Override
    public String toString() { return "LinkList" + rids(); }
}
