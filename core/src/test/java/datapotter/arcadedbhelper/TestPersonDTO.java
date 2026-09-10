package datapotter.arcadedbhelper;

import lombok.Data;
import static datapotter.arcadedbhelper.SchemaBuilder.defType;
import datapotter.datahelper.DataHelper;

/**
 * Test DTO for demonstrating ArcadeDB integration.
 * This will be processed by the DataHelper annotation processor.
 */
@DataHelper
@Data
public class TestPersonDTO implements TestPersonDTO_I<TestPersonDTO>, ArcadeDoc_I<TestPersonDTO> {
    String name;
    String email;
    Integer age;

    /**
     * Creates a TypeDef for schema initialization.
     * This method should be called to initialize the schema.
     */
    public static TypeDef<TestPersonDTO> TYPEDEF = 
            defType(TestPersonDTO.class)
                .fields(FIELDS)  // Type-safe! Accepts List<Field_I<TestPersonDTO, ?>>
                .unique($email)  // Type-safe! Only accepts Field_I<TestPersonDTO, ?>
                .__();
    
}
