package datapotter.arcadedbhelper.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.*;
import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.ArcadeDoc_I;
import datapotter.datahelper.processor.util.CodeGeneratorUtils;
import datapotter.datahelper.processor.util.FieldAnalyzer;
import datapotter.datahelper.processor.util.FieldInfo;
import datapotter.datahelper.processor.util.ProcessorUtils;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Annotation processor that generates {ClassName}_A sealed abstract parent class.
 *
 * <p>Generated code includes:
 * <ul>
 *   <li>Sealed abstract class that permits only the annotated class</li>
 *   <li>Field symbols ($fieldName constants)</li>
 *   <li>FIELDS list (immutable)</li>
 *   <li>Delegating getters/setters (access child's package-private fields)</li>
 *   <li>Fluent accessors</li>
 *   <li>DataHelper_I implementation (15 property accessor methods)</li>
 *   <li>ArcadeDoc_I implementation</li>
 *   <li>typeDef() schema builder helper</li>
 * </ul>
 *
 * <p>The child class extends the generated sealed abstract class and declares
 * package-private fields that the parent accesses via the {@code sub} reference.</p>
 *
 * <h3>Example Usage:</h3>
 * <pre>
 * {@code @ArcadeData}
 * public final class Person extends Person_A {
 *     String name;
 *     String email;
 *     Integer age;
 * }
 * </pre>
 *
 * <p>This eliminates Lombok dependency and reduces boilerplate compared to
 * the {@code @DataHelper} + {@code @Data} pattern.</p>
 *
 * @see ArcadeData
 * @see datapotter.datahelper.DataHelper_I
 * @see ArcadeDoc_I
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("datapotter.arcadedbhelper.ArcadeData")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ArcadeDataProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.CLASS) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "Processing @ArcadeData on " + element);
                    generateAbstractClass((TypeElement) element);
                }
            }
        }
        return true;
    }

    private void generateAbstractClass(TypeElement element) {
        String packageName = processingEnv.getElementUtils().getPackageOf(element).toString();
        String className = element.getSimpleName().toString();
        String abstractClassName = className + "_A";

        // Read the type parameter from @ArcadeData annotation
        ArcadeData annotation = element.getAnnotation(ArcadeData.class);
        String arcadeType = annotation.type().name(); // "DOCUMENT", "VERTEX", or "EDGE"

        // Initialize utilities with extended annotation list
        // Include BASE annotations (@DataHelper, @Data) + @ArcadeData
        Set<String> arcadeAnnotations = new HashSet<>(ProcessorUtils.BASE_DATA_HELPER_ANNOTATIONS);
        arcadeAnnotations.add("datapotter.arcadedbhelper.ArcadeData");

        ProcessorUtils utils = new ProcessorUtils(processingEnv, arcadeAnnotations);
        FieldAnalyzer analyzer = new FieldAnalyzer(processingEnv, utils);

        // Analyze fields (includes validation)
        List<FieldInfo> fields = analyzer.analyzeFields(element);
        if (fields == null) {
            return; // Validation errors found
        }

        // Enum fields — bare (PRP-28 phase 1) or a List of them (PRP-30) — need one of @AsUuid/@AsName
        // to be storable here. The base analyzer accepts any enum structurally — "cannot store an
        // enum" is a fact about THIS backend, not a universal one — so the rule is enforced here.
        boolean hasUnsupportedEnum = false;
        for (FieldInfo f : fields) {
            if (f.isAnyEnum() && !f.isEnumAsUuid && !f.isEnumAsName) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        String.format(
                            "Field '%s' is declared '%s', whose enum type '%s' carries neither @AsUuid nor "
                            + "@AsName. ArcadeDB cannot store an enum without a declared string encoding: "
                            + "annotate '%s' with @AsUuid(<encoding>) (and implement HasUuid) or with @AsName.",
                            f.name, f.type, f.enumType, f.enumType),
                        element);
                hasUnsupportedEnum = true;
            }
        }
        if (hasUnsupportedEnum) {
            return;
        }

        // Warn (don't fail) when a data field is spelled like a reserved graph accessor: the $rid
        // symbol then refers to the user's data field while $rid()/$out()/$in() remain the
        // identity/endpoint accessors. Storage uses __rid/__out/__in so it still compiles.
        for (FieldInfo f : fields) {
            if (f.name.equals("rid") || f.name.equals("out") || f.name.equals("in")) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Field '" + f.name + "' shares its spelling with the generated $" + f.name
                        + "() accessor; the $" + f.name + " symbol now refers to your data field. "
                        + "Consider renaming to avoid confusion.", element);
            }
        }

        // Build sealed abstract class
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(abstractClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT, Modifier.SEALED)
                .addPermittedSubclass(ClassName.get(packageName, className))
                .addJavadoc("Sealed abstract base class for {@link $L}.\n", className)
                .addJavadoc("Generated by @ArcadeData annotation processor.\n")
                .addJavadoc("\n<p>This class implements both DataHelper_I and ArcadeDoc_I,\n")
                .addJavadoc("providing all accessor methods via delegation to the child class.</p>\n");

        // Implement DataHelper_I and ArcadeDoc_I
        classBuilder.addSuperinterface(ParameterizedTypeName.get(
                ClassName.get("datapotter.datahelper", "DataHelper_I"),
                ClassName.get(packageName, className)
        ));
        classBuilder.addSuperinterface(ParameterizedTypeName.get(
                ClassName.get("datapotter.arcadedbhelper", "ArcadeDoc_I"),
                ClassName.get(packageName, className)
        ));

        // Add 'sub' field for delegation (opposite of 'super')
        FieldSpec subField = FieldSpec.builder(
                        ClassName.get(packageName, className),
                        "sub",
                        Modifier.PRIVATE, Modifier.FINAL)
                .initializer("($T) this", ClassName.get(packageName, className))
                .build();
        classBuilder.addField(subField);

        // Add field symbols ($fieldName constants).
        // @ArcadeData keeps FIELDS on the sealed _A base (no record projection for v1), so nested
        // chaining symbols reference <Nested>_A.FIELDS.
        CodeGeneratorUtils.addFieldSymbols(classBuilder, fields, packageName, className, "_A");

        // Add FIELDS list
        CodeGeneratorUtils.addFieldsList(classBuilder, fields, packageName, className);

        // Add static __ field for class name (terminal indicator pattern)
        FieldSpec classNameField = FieldSpec.builder(
                        String.class,
                        "__",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", className)
                .build();
        classBuilder.addField(classNameField);

        // Add schemaBuilder() helper
        addTypeDefHelper(classBuilder, packageName, className, arcadeType);

        // Add delegating getters
        for (FieldInfo field : fields) {
            // For boolean types, generate BOTH "is" and "get" getters for maximum compatibility
            if (ProcessorUtils.isBooleanType(field.type)) {
                // Generate isXxx() method
                String isGetterName = "is" + ProcessorUtils.capitalize(field.name);
                MethodSpec isGetter = MethodSpec.methodBuilder(isGetterName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(field.type)
                        .addStatement("return sub.$N", field.name)
                        .build();
                classBuilder.addMethod(isGetter);

                // Generate getXxx() method (delegates to isXxx for consistency)
                String getGetterName = "get" + ProcessorUtils.capitalize(field.name);
                MethodSpec getGetter = MethodSpec.methodBuilder(getGetterName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(field.type)
                        .addStatement("return $N()", isGetterName)
                        .build();
                classBuilder.addMethod(getGetter);
            } else {
                // For non-boolean types, use standard "get" prefix
                String getterName = "get" + ProcessorUtils.capitalize(field.name);
                MethodSpec getter = MethodSpec.methodBuilder(getterName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(field.type)
                        .addStatement("return sub.$N", field.name)
                        .build();
                classBuilder.addMethod(getter);
            }
        }

        // Add delegating setters
        for (FieldInfo field : fields) {
            String setterName = "set" + ProcessorUtils.capitalize(field.name);
            MethodSpec setter = MethodSpec.methodBuilder(setterName)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(field.type, field.name)
                    .addStatement("sub.$N = $N", field.name, field.name)
                    .build();
            classBuilder.addMethod(setter);
        }

        // Add fluent getters
        for (FieldInfo field : fields) {
            // Use "is" prefix for boolean types (primitive boolean and Boolean wrapper)
            String prefix = ProcessorUtils.isBooleanType(field.type) ? "is" : "get";
            String getterName = prefix + ProcessorUtils.capitalize(field.name);
            MethodSpec fluentGetter = MethodSpec.methodBuilder(field.name)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(field.type)
                    .addStatement("return $N()", getterName)
                    .build();
            classBuilder.addMethod(fluentGetter);
        }

        // Add fluent setters (return 'sub' for chaining)
        for (FieldInfo field : fields) {
            String setterName = "set" + ProcessorUtils.capitalize(field.name);
            MethodSpec fluentSetter = MethodSpec.methodBuilder(field.name)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(field.type, field.name)
                    .returns(ClassName.get(packageName, className))
                    .addStatement("$N($N)", setterName, field.name)
                    .addStatement("return sub")
                    .build();
            classBuilder.addMethod(fluentSetter);
        }

        // ========== DataHelper_I Methods (15 property accessor methods) ==========
        // 0. dataClass()
        classBuilder.addMethod(CodeGeneratorUtils.createDataClassMethod(packageName, className, false));

        // 1. fieldNames()
        classBuilder.addMethod(CodeGeneratorUtils.createFieldNamesMethod(false));

        // 2. getPropertyByName(String)
        classBuilder.addMethod(CodeGeneratorUtils.createGetPropertyByNameMethod(fields, utils, false));

        // 3. setPropertyByName(String, Object)
        classBuilder.addMethod(CodeGeneratorUtils.createSetPropertyByNameMethod(fields, utils, false));

        // 4. getPropertyType(String)
        classBuilder.addMethod(CodeGeneratorUtils.createGetPropertyTypeMethod(fields, false));

        // 5. createNestedObject(String)
        classBuilder.addMethod(CodeGeneratorUtils.createNestedObjectMethod(fields, false));

        // 6. createListElement(String)
        classBuilder.addMethod(CodeGeneratorUtils.createListElementMethod(fields, false));

        // 7. isListField(String)
        classBuilder.addMethod(CodeGeneratorUtils.createIsListFieldMethod(fields, false));

        // 8. isNestedObjectField(String)
        classBuilder.addMethod(CodeGeneratorUtils.createIsNestedObjectFieldMethod(fields, false));

        // 9-14. Map support methods (read metadata + write factories)
        CodeGeneratorUtils.addMapReadMethods(classBuilder, fields, false);
        CodeGeneratorUtils.addMapWriteMethods(classBuilder, fields, false);

        // 15. Static factory method: of(Document)
        ClassName entityClassName = ClassName.get(packageName, className);
        MethodSpec ofMethod = MethodSpec.methodBuilder("of")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(ClassName.get("com.arcadedb.database", "Document"), "doc")
                .returns(entityClassName)
                .addStatement("var instance = new $T()", entityClassName)
                .addStatement("return instance.fromArcadeDocument(doc)")
                .build();
        classBuilder.addMethod(ofMethod);

        // ========== Identity ($rid) — overrides ArcadeDoc_I defaults, set by the load layer ==========
        // Storage field is __rid (not $rid) so it can never collide with the auto-generated $<field>
        // symbol of a data field named "rid"; the public accessor stays $rid().
        ClassName ownerClass = ClassName.get(packageName, className);
        classBuilder.addField(FieldSpec.builder(String.class, "__rid", Modifier.PRIVATE).build());
        classBuilder.addMethod(MethodSpec.methodBuilder("$rid")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(String.class)
                .addStatement("return this.$N", "__rid")
                .build());
        classBuilder.addMethod(MethodSpec.methodBuilder("$rid")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .addParameter(String.class, "rid")
                .addStatement("this.$N = rid", "__rid")
                .build());

        // ========== Edge endpoints ($out / $in) — only for EDGE types ==========
        // Storage fields are __out/__in (not $out/$in) so they never collide with the auto-generated
        // $<field> symbol of a data field named "out"/"in"; public accessors stay $out()/$in().
        if ("EDGE".equals(arcadeType)) {
            ClassName linkType = ClassName.get("datapotter.arcadedbhelper", "Link");
            TypeName linkWild = ParameterizedTypeName.get(linkType, WildcardTypeName.subtypeOf(Object.class));
            for (String end : List.of("out", "in")) {
                String method = "$" + end, fld = "__" + end;
                classBuilder.addField(FieldSpec.builder(String.class, fld, Modifier.PRIVATE).build());
                classBuilder.addMethod(MethodSpec.methodBuilder(method)
                        .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                        .returns(linkWild)
                        .addStatement("return this.$N == null ? null : $T.ofRid(this.$N)", fld, linkType, fld)
                        .build());
                classBuilder.addMethod(MethodSpec.methodBuilder(method)
                        .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                        .addParameter(String.class, "rid")
                        .addStatement("this.$N = rid", fld)
                        .build());
            }
        }

        // ========== Reference (LINK) support — only when the class actually has reference fields ==========
        boolean hasLinks = fields.stream().anyMatch(f -> f.isLink || f.isLinkList || f.isLinkMap);
        if (hasLinks) {
            classBuilder.addMethod(CodeGeneratorUtils.createIsLinkFieldMethod(fields, false));
            classBuilder.addMethod(CodeGeneratorUtils.createIsLinkListFieldMethod(fields, false));
            classBuilder.addMethod(CodeGeneratorUtils.createIsLinkMapFieldMethod(fields, false));
            classBuilder.addMethod(CodeGeneratorUtils.createLinkTargetTypeMethod(fields, false));
            classBuilder.addMethod(CodeGeneratorUtils.createLinkKeyTypeMethod(fields, false));
            classBuilder.addMethod(CodeGeneratorUtils.createLinkTargetFactoryMethod(fields, false));

            // Convenience fluent setters for single Link<T> fields: customer(Customer) / customer(rid) / customerOfRid(rid).
            // The base setter customer(Link<T>) is already generated by the standard fluent-setter loop.
            ClassName linkClass = ClassName.get("datapotter.arcadedbhelper", "Link");
            for (FieldInfo f : fields) {
                if (!f.isLink) continue;
                String setterName = "set" + ProcessorUtils.capitalize(f.name);
                classBuilder.addMethod(MethodSpec.methodBuilder(f.name)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(f.linkTargetType, "value")
                        .returns(ownerClass)
                        .addStatement("$N($T.of(value))", setterName, linkClass)
                        .addStatement("return sub")
                        .build());
                classBuilder.addMethod(MethodSpec.methodBuilder(f.name)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(String.class, "rid")
                        .returns(ownerClass)
                        .addStatement("$N($T.ofRid(rid, $T::new))", setterName, linkClass, f.linkTargetType)
                        .addStatement("return sub")
                        .build());
                classBuilder.addMethod(MethodSpec.methodBuilder(f.name + "OfRid")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(String.class, "rid")
                        .returns(ownerClass)
                        .addStatement("return $N(rid)", f.name)
                        .build());
            }

            // Convenience setters for reference collections: reviewers(List<Target>) and
            // ledgers(Map<K,Target>) build the carrier from target objects via Link.of. The base
            // reviewers(LinkList)/ledgers(LinkMap) setters are already generated by the standard loop.
            ClassName linkListClass = ClassName.get("datapotter.arcadedbhelper", "LinkList");
            ClassName linkMapClass = ClassName.get("datapotter.arcadedbhelper", "LinkMap");
            ClassName listClass = ClassName.get("java.util", "List");
            ClassName mapClass = ClassName.get("java.util", "Map");
            ClassName linkedHashMapClass = ClassName.get("java.util", "LinkedHashMap");
            for (FieldInfo f : fields) {
                String setterName = "set" + ProcessorUtils.capitalize(f.name);
                if (f.isLinkList) {
                    classBuilder.addMethod(MethodSpec.methodBuilder(f.name)
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(ParameterizedTypeName.get(listClass, f.linkTargetType), "targets")
                            .returns(ownerClass)
                            .addStatement("$N($T.of(targets.stream().map($T::of).toList()))",
                                    setterName, linkListClass, linkClass)
                            .addStatement("return sub")
                            .build());
                } else if (f.isLinkMap) {
                    classBuilder.addMethod(MethodSpec.methodBuilder(f.name)
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(ParameterizedTypeName.get(mapClass, f.linkMapKeyType, f.linkTargetType), "targets")
                            .returns(ownerClass)
                            .addStatement("var m = new $T<$T, $T<$T>>()",
                                    linkedHashMapClass, f.linkMapKeyType, linkClass, f.linkTargetType)
                            .addStatement("targets.forEach((k, v) -> m.put(k, $T.of(v)))", linkClass)
                            .addStatement("$N($T.of(m))", setterName, linkMapClass)
                            .addStatement("return sub")
                            .build());
                }
            }
        }

        // ========== Enum field support (Phase 1, PRP-28) — only when the class has enum fields ==========
        CodeGeneratorUtils.addEnumSupport(classBuilder, fields, false);

        // Build and write the file
        TypeSpec classSpec = classBuilder.build();
        JavaFile javaFile = JavaFile.builder(packageName, classSpec)
                .indent("    ")
                .skipJavaLangImports(true)
                .addFileComment("Generated by ArcadeDataProcessor on " + LocalDateTime.now())
                .addStaticImport(ClassName.get("datapotter.arcadedbhelper", "SchemaBuilder"), "defType")
                .build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated sealed abstract class: " + abstractClassName + " for class: " + className);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate sealed abstract class: " + e.getMessage());
        }
    }

    /**
     * Generate schemaBuilder() helper that pre-fills class, fields, and type.
     * User only needs to specify unique constraints, indexes, etc.
     *
     * <pre>
     * public static SchemaBuilder{@code <Person>} schemaBuilder() {
     *     return defType(Person.class, ArcadeType.VERTEX).fields(FIELDS);
     * }
     * </pre>
     *
     * @param arcadeType The type from @ArcadeData annotation ("DOCUMENT", "VERTEX", or "EDGE")
     */
    private void addTypeDefHelper(TypeSpec.Builder classBuilder, String packageName, String className, String arcadeType) {
        MethodSpec schemaBuilderHelper = MethodSpec.methodBuilder("schemaBuilder")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(
                        ClassName.get("datapotter.arcadedbhelper", "SchemaBuilder"),
                        ClassName.get(packageName, className)))
                .addStatement("return defType($T.class, $T.$L).fields(FIELDS)",
                        ClassName.get(packageName, className),
                        ClassName.get("datapotter.arcadedbhelper", "ArcadeType"),
                        arcadeType)
                .build();
        classBuilder.addMethod(schemaBuilderHelper);
    }
}
