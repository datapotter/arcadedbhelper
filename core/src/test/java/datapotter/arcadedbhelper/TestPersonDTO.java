package datapotter.arcadedbhelper;

import static datapotter.arcadedbhelper.SchemaBuilder.defType;
import datapotter.datahelper.DataHelper;

/**
 * Test DTO for demonstrating ArcadeDB integration.
 * This will be processed by the DataHelper annotation processor.
 *
 * <p>The accessors below are written by hand ON PURPOSE. {@code @DataHelper} generates an interface
 * whose setters are ABSTRACT — it is the "bring your own accessors" path, as distinct from
 * {@code @Data}, which generates them. Lombok used to supply them here, which made Lombok a
 * dependency of this whole module for the sake of one test class. Six methods are cheaper than a
 * dependency, and writing them keeps the {@code @DataHelper} path under test without one.
 */
@DataHelper
public class TestPersonDTO implements TestPersonDTO_I<TestPersonDTO>, ArcadeDoc_I<TestPersonDTO> {
    String name;
    String email;
    Integer age;

    @Override public String  getName()  { return name; }
    @Override public String  getEmail() { return email; }
    @Override public Integer getAge()   { return age; }

    @Override public void setName(String name)   { this.name = name; }
    @Override public void setEmail(String email) { this.email = email; }
    @Override public void setAge(Integer age)    { this.age = age; }

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
