# DataHelper

Compile-time code generator (annotation processor) for Java 21+ DTOs. For each annotated class it generates:

- **Type-safe field symbols** — `$name`, `$email` constants (`Field<Owner,Type>`) + a `FIELDS` list. Refer to fields by symbol, not stringly-typed names.
- **Reflection-free property access** — `getPropertyByName` / `setPropertyByName` / `getPropertyType` backed by `switch` (no reflection; works on TeaVM/GraalVM native).
- **Bean + fluent accessors** — `getName()`/`setName()` and `name()`/`name(v)` (fluent setter returns `this`).
- **Pluggable serialization traits** — JSON, ArcadeDB, etc. are *interfaces with default methods* that build on the property accessors. Add a trait by adding it to the `implements` clause.

It is a Lombok alternative that also works *alongside* Lombok. Lombok generates accessors at the source level; DataHelper additionally gives you reflection-free metadata + serialization. Output is plain generated `.java` — readable, debuggable, no runtime agent.

Other advantage of datahelper
- One advantage over lombok is that the functions (getters/setters) are really there created physically, so you can use your IDE (like netbeans) to inspect and find usages and know the effects of refactoring, and possibly even initiate refactor. For IntelliJ (which has a plugin for lombok) probably this will not matter that much. 
- Lombok is brittle it breaks after every new jdk release and then the library maintainers fight with the changes and fix it, datahelper will be more stable and will probably work even after 10 years of not even maintaining. 

## Do you even need this library?

The one deciding question: **do you need any of DataHelper's distinguishing features?**

- type-safe field symbols (`$name`, `FIELDS`),
- reflection-free property access (`getPropertyByName` / `setPropertyByName` / `getPropertyType`),
- pluggable serialization traits (JSON, ArcadeDB, or your own),
- and, because none of the above uses reflection, operation on TeaVM / GraalVM-native.

If you need **none** of those, plain Java or Lombok is simpler — don't reach for DataHelper. DataHelper is new; adopt it by addition, not by rewrite.

Keep the two `@Data` annotations straight — disambiguate by import:

- `lombok.Data` — Lombok's; writes accessors. `@DataHelper` generates the interface split `Xxx_IR` (readable) + `Xxx_I` (read+write) — symbols + *abstract* accessor declarations + property accessors — plus the immutable record `Xxx_R`, so it **needs** Lombok (or hand-written getters/setters) to supply the accessor bodies on the mutable class. This is the "with Lombok" pairing.
- `xyz.jphil.datahelper.Data` — DataHelper's own; generates the same `Xxx_IR`/`Xxx_I`/`Xxx_R` plus a self-contained sealed `Xxx_A` parent that writes the accessor bodies (delegating to the child) **and** `equals`/`hashCode`/`toString`; the property/fluent methods and `toRecord()` are inherited from the generated interfaces. Used alone, **no Lombok**.

Both paths expose the identical symbol + property-accessor API.

### Conclusions

- **Immutable carrier, none of the features needed** → a Java `record` is the simplest thing. (DataHelper also *generates* an immutable record projection — see [Immutable record projection](#immutable-record-projection) — giving you immutability **and** the symbol/serialization API.)
- **Existing Lombok project, only boilerplate needed** → Lombok alone; don't add DataHelper just to have it. For a *mutable* DTO the fluent POJO already covers construction — `@Builder`/`@With` only earn their keep on *immutable* types — so Lombok's remaining edge is mostly an all-args constructor and ecosystem familiarity.
- **Already on Lombok and now need a DataHelper feature on some DTO** → keep Lombok, add `@DataHelper` beside `lombok.@Data` (or `@Getter @Setter`). Lombok keeps writing accessors; DataHelper adds the symbols, reflection-free access, and traits. Lowest-friction adoption — the transition path, no migration.
- **New code, no Lombok wanted, or a TeaVM/GraalVM-native target** → DataHelper's own `@Data` alone. It generates accessors, fluent methods, symbols, property accessors, **and** `equals`/`hashCode`/`toString` — for a mutable DTO you give up essentially nothing vs Lombok. Accept the `_A` constraints: the class is `final`, `extends Xxx_A`, with package-private fields.

**Default stance:** new mutable DTOs (or any needing the symbol/serialization API) → DataHelper's `@Data`; pure immutable carriers → `record`; existing Lombok DTOs → leave them, reach for `@DataHelper` only where a feature is actually needed.

> Note: DataHelper's generated `equals`/`hashCode` are **value-based over all fields** (like Lombok `@Data`). Same footgun: mutating a field while the DTO sits in a `HashSet`/`HashMap` key breaks it. The immutable record projection (`toRecord()`) is the clean fix; a `final` child class can also override any of the three for key-based identity.

## Maven

```xml
<dependencies>
  <dependency>
    <groupId>io.github.xyz-jphil</groupId>
    <artifactId>xyz-jphil-datahelper-base</artifactId>
    <version>1.0</version>
  </dependency>
  <dependency>
    <groupId>io.github.xyz-jphil</groupId>
    <artifactId>xyz-jphil-datahelper-annotations</artifactId>
    <version>1.0</version>
  </dependency>
</dependencies>

<build><plugins><plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.github.xyz-jphil</groupId>
        <artifactId>xyz-jphil-datahelper-processor</artifactId>
        <version>1.0</version>
      </path>
      <!-- add the lombok path here too, only if using @DataHelper mode -->
    </annotationProcessorPaths>
  </configuration>
</plugin></plugins></build>
```

Optional: `xyz-jphil-datahelper-json` (JSON trait, JVM-only). The generated source appears under `target/generated-sources/annotations`.

## Usage — no Lombok (DataHelper's `@Data`)

Recommended when Lombok is not available. The class must be `final`, extend the generated `Xxx_A`, and declare **package-private** fields (no modifier) — the sealed parent delegates to them.

```java
import xyz.jphil.datahelper.Data;   // DataHelper's @Data — NOT lombok.Data

@Data
public final class Person extends Person_A {   // Person_A is generated
    String name;
    String email;
    Integer age;
}
```

```java
var p = new Person().name("Ann").age(30);      // fluent setters chain
p.getName();                                   // "Ann"  (bean getter)
p.name();                                      // "Ann"  (fluent getter)
Person.$email.name();                          // "email" — compile-checked field name
Person.$email.type();                          // String.class
p.getPropertyByName("age");                    // 30     — reflection-free dynamic read
p.setPropertyByName("age", "31");              // value coerced to Integer
Person.FIELDS.forEach(f -> ...);               // iterate all field symbols
```

## Usage — with Lombok (`@DataHelper`)

Use when Lombok is already on the classpath. Lombok writes the accessors; DataHelper generates the `Xxx_I` interface the class implements.

```java
import lombok.Data;                          // Lombok's @Data — NOT xyz.jphil.datahelper.Data
import xyz.jphil.datahelper.DataHelper;

@DataHelper
@Data                                        // Lombok (or @Getter @Setter) — supplies the accessor bodies
public class Person implements Person_I<Person> {   // Person_I is generated
    String name;
    String email;
    Integer age;
}
```

Same API as above (`$name`, `FIELDS`, `name()`, `getPropertyByName(...)`, …). Add Lombok to `annotationProcessorPaths` alongside the DataHelper processor.

`@DataHelper` also accepts `propertyAnnotations` / `superInterfaces` (e.g. `@DataHelper(propertyAnnotations = {JSProperty.class}, superInterfaces = {JSObject.class})`) to decorate generated accessors for frameworks like TeaVM.

## JSON serialization

Add the `xyz-jphil-datahelper-json` dependency. No reflection, no external JSON library. The trait is split: `Json_IR` is the read side (`toJson`), `Json_I extends Json_IR` adds the write side (`fromJson`).

```java
// Mutable only — add the full trait to the implements clause:
// no-Lombok (DataHelper @Data):  public final class Person extends Person_A implements Json_I<Person>
// with-Lombok (@DataHelper):      public class Person implements Person_I<Person>, Json_I<Person>

var json = p.toJson();              // deep (recurses nested/list/map); skips null fields
var shallow = p.toJson(false);      // nested objects not expanded
var q = new Person().fromJson(json);
```

To also give the **record projection** `toJson`, declare the read-side trait via `superInterfaces` so it lands on `Person_IR` (and the processor auto-routes the `fromJson` write half onto the mutable `Person_I`):

```java
@DataHelper(superInterfaces = {Json_IR.class})
public class Person implements Person_I<Person> { ... }

var j = p.toRecord().toJson();      // record carries toJson via Person_IR
```

JVM-only — avoid on TeaVM (use `JSObject` directly there).

## Jackson interop

DataHelper DTOs are standard beans (`getX`/`setX`), so Jackson serializes/deserializes them out of the box in **both** modes — including the no-Lombok `@Data` path, where the accessors are inherited from the generated `_A` parent (Jackson walks the class hierarchy). The extra fluent (`name()`) and utility (`getPropertyByName(String)`, `dataClass()`, `fieldNames()`) methods aren't bean-shaped, so Jackson ignores them.

Customize with Jackson annotations on the **field** — Jackson merges field annotations onto the property even when the accessor is generated:

```java
@Data
public final class Person extends Person_A {
    @JsonProperty("full_name") String name;
    @JsonIgnore String secret;
    @JsonFormat(shape = STRING) Integer age;
}
```

In the `@DataHelper` + Lombok path you can additionally use `lombok.config`'s `lombok.copyableAnnotations` to copy field annotations onto Lombok's accessors.

This applies to **Jackson**. DataHelper's *own* built-in `toJson()` serializes by raw field name and ignores these annotations — use Jackson when you need renaming/formatting/polymorphism, the built-in serializer for the minimal, zero-dependency, reflection-free case.

## Nested objects, Lists, Maps

Traits serialize recursively **when the element type is itself a DataHelper DTO**. Make nested types `@DataHelper`/`@Data` too.

```java
@Data public final class Company extends Company_A {
    String name;
    Address address;             // nested DataHelper DTO  -> recursed
    List<Address> branches;      // List of DTOs           -> recursed
    Map<String, Address> sites;  // Map with DTO values     -> recursed
}
```

Supported value kinds, both directions: nested DTO, `List<DTO>`, `Map<K, DTO>`, and plain scalars/collections. Non-string `Map` keys are coerced via `DataHelper_I.convertType`.

## Reflection-free dynamic access

Every generated type implements `DataHelper_I`, the contract all traits build on. Use it directly to write your own binder/serializer/validator without reflection:

```java
var cls   = p.dataClass();                   // Person.class
var names = p.fieldNames();                  // ["name","email","age"]
var t     = p.getPropertyType("age");        // Integer.class
var v     = p.getPropertyByName("age");
p.setPropertyByName("age", value);           // auto-converts numeric/string
// metadata for containers:
p.isListField(f); p.isMapField(f); p.isNestedObjectField(f);
p.createNestedObject(f); p.createListElement(f); p.createMapValueElement(f);
```

`DataHelper_I.convertType(value, targetType)` handles the common String/Number ↔ boxed-primitive coercions.

## Immutable record projection

Every DTO gets a generated immutable record `Person_R` that shares a **readable** interface (`Person_IR`) with the mutable class:

- `Person_IR` (readable: getters, `$symbols`, `FIELDS`, read-side property access) is the shared super.
- `Person_I` (full, read+write) `extends Person_IR` — implemented by the mutable class.
- `Person_R` is a `record` implementing `Person_IR` — immutable, safely hashable (record-native value `equals`/`hashCode`), no setters.
- Conversions: `mutable.toRecord()` → `Person_R`, `record.toMutable()` → `Person` (plus a static `Person.from(record)`); **deep** for nested DTOs/Lists/Maps (`Address` → `Address_R`, `List<Address>` → `List<Address_R>`, `Map<K,Address>` → `Map<K,Address_R>`).

`_IR` is **readable**, not read-only/immutable: a mutable `Person` is also a `Person_IR`, so a `Person_IR` reference only promises "you can read through this," not that the object never changes — immutability is the record's (`_R`) guarantee. An API taking `Person_IR` therefore accepts both the mutable DTO and the record. This gives an immutable, correctly-hashing snapshot (avoiding the mutable-in-a-`HashSet` footgun) while keeping the full symbol + serialization API.

```java
var p = new Person();
p.setName("Ada");
p.setHome(new Address(/* ... */));         // nested DTO

var snapshot = p.toRecord();               // deep: home is now an Address_R
snapshot.getName();                        // "Ada" — typed read
snapshot.home().getCity();                 // nested read through Address_IR
set.add(snapshot);                         // safe HashSet key (value semantics, never mutates)

var again  = snapshot.toMutable();         // deep round-trip back to mutable
var again2 = Person.from(snapshot);        // equivalent static factory
```

Nested DTO fields must be declared as the **concrete** type (`Address`), not a generated variant (`Address_IR`/`Address_I`/`Address_R`/`Address_A`); the processor derives those itself and rejects variant declarations with a clear error.

**Traits on records.** A read-side trait declared via `@DataHelper(superInterfaces = {Json_IR.class})` is mixed into `Person_IR`, so the record gets it too — e.g. `personRecord.toJson()`. The processor automatically routes the trait's write half (`Json_I`, providing `fromJson`) onto the mutable `Person_I` only; records aren't deserialized into directly (parse into the mutable form, then `toRecord()`).

### vs. record-builder

[record-builder](https://github.com/Randgalt/record-builder) and DataHelper are both pure compile-time generators (no runtime reflection) producing immutable, value-semantic records with immutable collections. record-builder centers on immutable-record *construction* ergonomics — and DataHelper's fluent mutable + record round-trip covers those, usually more simply:

- **builder / `build()`** → the fluent mutable POJO *is* the builder: `new Person().name("x").age(5)`; `.toRecord()` is `build()`.
- **withers (`withX` / `with(Consumer)`)** → `r.toMutable().name("x").toRecord()` — a new record, original untouched. One expression, any number of fields, arbitrary logic in between; no per-field `withX` or `Consumer<Builder>` ceremony.
- **`@Initializer` defaults** → a plain field initializer (`int age = 18;`), captured by `toRecord()`.
- **define-once → record + interface** → one annotated class yields `_IR` + `_I` + `_R` (record *and* interfaces), vs record-builder's `@RecordInterface` yielding a record from an interface.

On top of that DataHelper has what record-builder has no equivalent for: field symbols (`$name`, `FIELDS`), reflection-free by-name access, serialization traits (JSON/ArcadeDB/your own), TeaVM/GraalVM-native operation, the readable↔writable split, and mutable-class + Lombok interop.

The only genuine record-builder-only things, both minor: a **staged builder**'s compile-time guarantee that every required field is set before you obtain a record (the round-trip lets you `toRecord()` a half-filled object), and — for deeply nested graphs in hot paths — a single-field change round-trips through two deep copies where a record wither shallow-copies.

## Modules

All under group `io.github.xyz-jphil`:

- `xyz-jphil-datahelper-base` — runtime: `DataHelper_IR` (readable) / `DataHelper_I` (read+write), `Field`/`Field_I`, `convertType`.
- `xyz-jphil-datahelper-annotations` — `@DataHelper`, `@Data`.
- `xyz-jphil-datahelper-processor` — annotation processor (handles both annotations); generates `_IR`/`_I`/`_R` (+`_A` for `@Data`); goes on `annotationProcessorPaths` only.
- `xyz-jphil-datahelper-json` — optional JSON trait (JVM): `Json_IR` (`toJson`, read) / `Json_I` (`fromJson`, write).
- `xyz-jphil-arcadedb-datahelper` — optional ArcadeDB trait (this module).

## ArcadeDB integration (this module)

This module adds the `ArcadeDoc_I<Self>` trait for ArcadeDB persistence — an instance DSL plus document deserialization:

```java
public class Person implements Person_I<Person>, ArcadeDoc_I<Person> { ... }

var doc = person.in(db).whereEq("email", person.email()).upsert();       // or .insert()
new Person().fromArcadeDocument(doc);                                    // load back
```

Schema helpers (`SchemaBuilder`, `TypeDef`, `InitDoc`) and the full graph/vertex/edge/embedded-type DSL are out of scope here. For the complete, worked ArcadeDB tutorial see `project-journals/aracde_db_context/arcade-db-working-examples-2026-02-12.md`.
