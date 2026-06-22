# ArcadeDB DataHelper

The ArcadeDB persistence module for [DataHelper](../README.md). It adds an `@ArcadeData` annotation and an `ArcadeDoc_I` trait so a DataHelper DTO can define its [ArcadeDB](https://arcadedb.com) schema, upsert/insert itself, and load back from a `Document` — all on top of DataHelper's reflection-free property accessors (so the same code is fine on GraalVM-native).

> This is an optional add-on. For the core library — `@Data`/`@DataHelper`, field symbols, reflection-free access, JSON, and the immutable record projection — see the [main DataHelper README](../README.md).

## `@ArcadeData`

One annotation, no Lombok. The generated `Xxx_A` sealed parent supplies the accessors, the field symbols, the `DataHelper_I` + `ArcadeDoc_I` implementations, a pre-filled `schemaBuilder()`, and an `of(Document)` factory.

```java
@ArcadeData                                  // type() defaults to DOCUMENT; also VERTEX / EDGE
public final class Person extends Person_A {
    String name;                             // package-private (the _A parent delegates to these)
    String email;
    Integer age;

    public static final TypeDef<Person> TYPEDEF =
        schemaBuilder()                      // pre-filled with class + FIELDS
            .unique($email)                  // type-safe field symbol
            .__();
}
```

## Persisting and loading

`ArcadeDoc_I` gives each DTO an instance-level DSL plus document deserialization:

```java
var db = ...;                                                  // an ArcadeDB Database

var doc = person.in(db).whereEq($email, person.email()).upsert();   // or .insert()
var loaded = Person.of(doc);                                        // static factory
// or populate an existing instance:
new Person().fromArcadeDocument(doc);
```

Nested DataHelper DTOs, `List<DTO>`, and `Map<K,DTO>` (de)serialize recursively, the same as the JSON trait — `fromArcadeDocument` / `fromArcadeMap` walk them via the reflection-free accessors.

## Maven

```xml
<dependency>
    <groupId>io.github.xyz-jphil</groupId>
    <artifactId>xyz-jphil-arcadedb-datahelper</artifactId>
    <version>1.0</version>
</dependency>
```

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.github.xyz-jphil</groupId>
        <artifactId>xyz-jphil-arcadedb-datahelper-processor</artifactId>
        <version>1.0</version>
    </path>
</annotationProcessorPaths>
```

> **Two-compilation note:** the sealed `_A` parent is generated from the annotated class, which then `extends` it — a circular dependency that can need two passes on a first build. `mvn clean install` handles it; don't assume the code is broken if the very first compile complains about `Xxx_A`.

## Full tutorial

This README is the quick orientation. The complete, worked guide — VERTEX/EDGE graph modelling, links, embedded types, the database-service pattern, all CRUD operations, the type-safe vs string-based query API, transactions, and database-lifecycle do's and don'ts — lives in:

`project-journals/aracde_db_context/arcade-db-working-examples-2026-02-12.md`
