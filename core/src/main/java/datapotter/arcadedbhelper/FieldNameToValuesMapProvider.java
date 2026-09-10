package datapotter.arcadedbhelper;

import java.util.Map;

/**
 * Interface for objects that can provide their fields as a Map.
 * Used by the document update mechanism to extract field values.
 */
public interface FieldNameToValuesMapProvider {

    /**
     * Get a map of field names to values.
     *
     * @return map of field names to their values
     */
    Map<String, Object> map();
}
