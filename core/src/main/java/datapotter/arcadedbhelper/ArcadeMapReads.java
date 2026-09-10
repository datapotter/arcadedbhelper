package datapotter.arcadedbhelper;

import com.arcadedb.database.Document;
import com.arcadedb.query.sql.executor.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import datapotter.datahelper.DataHelper_I;
import datapotter.datahelper.MapReadContext;

/**
 * What ArcadeDB adds to the generic map deserialization in {@code base}: its own record types, and
 * references.
 *
 * <p>Stateless and shared. It is a {@link MapReadContext} rather than a set of overrides on
 * {@link ArcadeDoc_I} because the context has to stay in force through the whole descent: an
 * embedded block is a plain {@code @Data} type that knows nothing about ArcadeDB, yet the values
 * inside it are still ArcadeDB documents, so the knowledge cannot live on the object being
 * populated.</p>
 */
final class ArcadeMapReads implements MapReadContext {

    static final MapReadContext CONTEXT = new ArcadeMapReads();

    private ArcadeMapReads() {}

    /**
     * Three record forms reach the reader: a query {@link Result} (deep-mapped, so a nested
     * {@code :{}} projection resolves), an embedded {@link Document}, and a plain map. Anything
     * else is a scalar.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> asFieldMap(Object value) {
        return switch (value) {
            case Result r -> ArcadeReads.toDeepMap(r);
            case Document d -> propertyMap(d);
            case Map<?, ?> m -> (Map<String, Object>) m;
            default -> null;
        };
    }

    private static Map<String, Object> propertyMap(Document doc) {
        var m = new LinkedHashMap<String, Object>();
        for (var name : doc.getPropertyNames()) m.put(name, doc.get(name));
        return m;
    }

    /**
     * References. A {@code Link}/{@code LinkList}/{@code LinkMap} field holds RIDs, not embedded
     * copies, so it is read by {@link ArcadeDoc_I#toLink} rather than by the generic rules &mdash;
     * a projected sub-document comes back resolved, a bare RID unresolved, and neither performs I/O.
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean readTraitField(DataHelper_I<?> target, String fieldName, Object value) {
        if (!(target instanceof ArcadeDoc_I<?> doc)) return false;

        if (doc.isLinkField(fieldName)) {
            target.setPropertyByName(fieldName, doc.toLink(fieldName, value));
            return true;
        }
        if (doc.isLinkListField(fieldName) && value instanceof List<?> source) {
            var links = new ArrayList<Link<?>>(source.size());
            for (var item : source) links.add(doc.toLink(fieldName, item));
            target.setPropertyByName(fieldName, new LinkList(links));
            return true;
        }
        if (doc.isLinkMapField(fieldName) && value instanceof Map<?, ?> source) {
            var keyType = doc.linkKeyType(fieldName);
            var links = new LinkedHashMap<Object, Link<?>>();
            for (var entry : source.entrySet()) {
                links.put(DataHelper_I.convertType(entry.getKey(), keyType),
                        doc.toLink(fieldName, entry.getValue()));
            }
            target.setPropertyByName(fieldName, new LinkMap(links));
            return true;
        }
        return false;
    }
}
