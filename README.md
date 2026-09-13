# ArcadeDBHelper

A compile-time, reflection-free persistence layer for [ArcadeDB](https://arcadedb.com), built on [`datapotter/datahelper`](https://github.com/datapotter/datahelper)'s codegen core. One annotation on a plain Java class generates the schema definition, type-safe field symbols, a typed query/upsert/delete DSL, graph (vertex/edge/link) support — and a schema migration engine that detects a *rename* (type or property) from the source code alone and applies it safely, instead of reading it as a drop-and-add.

Not an ORM in the Hibernate sense — there is no session, no lazy proxy, no reflection anywhere. Every accessor, every schema property, every query field is a name the compiler checked, backed by a generated `switch`. That is also what makes it run unmodified on GraalVM native-image and TeaVM (Java-to-JS in the browser), which reflection-based mapping cannot.

> **Where this stands:** actively developed, `2.0-SNAPSHOT`, not yet on Maven Central. Docs are AI-assisted and the full worked tutorial is still private — this README is the orientation; [`core/README.md`](core/README.md) and the javadoc are the fuller reference. Everything claimed below has a jbang reproducer in the repo or was measured against a real ArcadeDB instance — see "Found while building this" near the end.

## Quick look

```java
@ArcadeData(id = "aK3f_9")                         // stable type identity — see "Schema evolution" below
public final class Person extends Person_A {       // Person_A is generated
    @P("o3KcQR") String name;                       // stable field identity — optional per field
    String email;
    Integer age;

    public static final TypeDef<Person> TYPEDEF =
        schemaBuilder().factory(Person::new).unique($email).__();
}

// Schema is created — or migrated — from these classes alone:
MigrationPlan plan = InitDoc.initDocTypes(db, Person.TYPEDEF);

// Typed, lazy queries. Neither a field name nor a type name is ever a bare string:
for (Person p : query(db, Person.TYPEDEF).eq($city, "Chennai").orderByAsc($name)) { ... }

db.transaction(() -> upsert(db, Person.TYPEDEF).key($email, email).set($age, 30).save());
```

## Schema evolution: renames become detectable, then safe

This is the newest and most distinctive part of the library, and the reason the [property-rename feature request](https://github.com/ArcadeData/arcadedb/issues/7589) against ArcadeDB itself exists.

**The problem every code-first mapper hits.** Diff a schema by name and a rename is indistinguishable from a delete plus an add — Hibernate's `hbm2ddl.auto=update` adds the new column and abandons the old one; EF Core scaffolds Drop+Add for you to hand-edit; Django is the honest one and just asks "did you rename X to Y?". None of them is being lazy — without a stable identity, the two cases really do look the same.

**So give every type and property one.** `@P("o3KcQR")` on a field, `@ArcadeData(id = "aK3f_9")` on a class — six base64url characters, five random plus one checksum character. Never hand-authored: a field with no `@P` under `requireIds = true` fails the build with a freshly minted id ready to paste, and any single-character typo of a real one fails the same way, at compile time, instead of silently orphaning a column later. Both are `SOURCE`-retained, so the id costs nothing at runtime and reaches schema init only through the generated field symbol (`Field_I.stableId()`) — no reflection, ever.

**Identity is optional at every granularity**, deliberately — a still-forming schema shouldn't have to commit to permanence before it has earned it. Annotate one field, some fields, a whole type, or nothing; whatever is identified gets rename detection, and the rest degrades exactly to today's name-based behaviour. The matching order is fixed: by id first, then by name, then reported as unmatched — never guessed at.

**What it does automatically, at schema init (`InitDoc.initDocTypes`):**
- A type matched by id under a new name is renamed outright, through a wrapper (`Rename.type`) that captures every index definition, drops the indexes, renames, and rebuilds them — because a bare engine rename on some ArcadeDB versions silently drops every index on the type (see below).
- A property matched by id under a new name is *detected* and reported, but never renamed in place — ArcadeDB has no `ALTER PROPERTY .. NAME`, so applying it means rewriting every row. That is collected into a `MigrationPlan` instead of being done implicitly.
- An id that vanishes from source is marked orphaned, never dropped — the ambiguity between "deleted field" and "commented out mid-refactor" is real, and guessing wrong destroys data.
- An edited id — same field, a different but valid id — is refused with an exception naming the collision, rather than silently creating a second property.

**Applying a detected rename is a deliberate, separate step**, on purpose:

```java
MigrationPlan plan = InitDoc.initDocTypes(db, Person.TYPEDEF, Order.TYPEDEF);
if (!plan.isEmpty()) {
    plan.print();                              // read-only — see what's pending
    plan.apply(db, backupPath);                 // backs up first, then applies
}
```

`apply` backs up the database, then runs a seven-statement recipe per rename — create the new property (Java API, carrying the old one's constraints and its own id), copy every row's value across, drop the indexes standing on the old property (ArcadeDB refuses to drop a property while one does), remove the old property, rebuild the indexes against the new name. Every step is journalled beside the database *before* the next one runs, so a crash mid-migration resumes from wherever it stopped rather than restarting or double-applying.

This is exactly the workaround described in the feature request: **it is what we built because the engine doesn't yet have an in-place property rename.** If it grows one, most of this collapses to a single statement.

## Everything else

- **Typed queries, upserts, deletes** — `eq/neq/lt/le/gt/ge/like/ilike/in/between/isNull/isNotNull`, lazy iteration, `firstOrNull/count/exists`, and a `Delete` verb that exists because bulk-deleting through raw SQL left ArcadeDB's own LSM indexes holding entries for records that no longer existed (measured: it happened at ~370k rows, silently, and later reads threw `RecordNotFoundException` mid-iteration). `Indexes.duringBulkChange` drops and rebuilds indexes around a large write, cutting a 50k-row bulk change from 142s to 2.9s in the same measurement.
- **Graph & references** — `Link<T>`/`LinkList<T>`/`LinkMap<K,T>` for RID-only references versus a DTO field that embeds a full copy; `$out`/`$in`/`$both` adjacency on vertices and `Traverse` for walking a held `Vertex` without a redundant fetch; query-shaped resolution (`SELECT *, customer:{*} ...`) comes back already resolved.
- **Enums with declared identity** — `@AsUuid`/`@AsName` on the enum (never the field, so there's no way to store one vocabulary two inconsistent ways), validated against the declared encoding's alphabet and length at the enum's own declaration; resolution never throws on an unrecognised value, because a row written by newer code is not a corrupt row.
- **Nested objects, lists, maps** — recurse the same reflection-free way the schema and the JSON trait do.

Full detail, with code, for all of the above: [`core/README.md`](core/README.md).

## Foundation: DataHelper

The reflection-free codegen this all sits on lives in the sibling repo, [`datapotter/datahelper`](https://github.com/datapotter/datahelper): field symbols, by-name property access backed by generated `switch`, pluggable serialization traits (JSON, this ArcadeDB module, or your own), an immutable record projection generated alongside the mutable class, and interop with plain Lombok DTOs for anyone who wants the symbols and traits without switching how accessors are written.

## Found while building this

Real usage against a real ArcadeDB instance surfaced engine behaviour worth being upfront about, because it's also why the migration engine above is as defensive as it is:

- Renaming a type destroyed every index on it, silently — correct in memory, gone at the next reopen, and a UNIQUE constraint stopped enforcing (fixed upstream in ArcadeDB 26.9.1; `Rename.type` guards against it on any version regardless).
- A query combining a null check with another condition and a `limit`, or a plain `skip`+`limit` page, returned far fewer rows than actually matched — [reported and root-caused upstream](https://github.com/ArcadeData/arcadedb/issues/6565).
- `BACKUP DATABASE` on Windows ignores its own path argument and, separately, produces an archive that can't be restored — [reported upstream](https://github.com/ArcadeData/arcadedb/issues/7586).

None of this is a knock on ArcadeDB — an embedded, single-writer, multi-model engine that's fast and pleasant to build on is a rare thing, which is the whole reason this library exists rather than reaching for something else. It's stated here because a persistence layer that hasn't been run hard enough to find these has not been tested, and because the migration engine's insistence on capturing-before-touching, journalling-before-acting, and refusing rather than guessing is a direct response to having watched an engine-level rename quietly eat a constraint.

## Roadmap

Honestly incomplete, in the spirit of the section above:

- **Three explicit schema modes** (`recreate` / `additive` / `strict`) as distinct types rather than a boolean beside an enum — not yet designed past the requirement that a hybrid mode should be unrepresentable, not merely undocumented.
- **Narrowing migrations** (`int → short`) decided by scanning the existing data at init, before the store opens for writes — safe here because ArcadeDB is embedded and single-writer, so there's no race to worry about.
- **A schema history table** recording what the schema looked like at each run — its real payoff is a backup restored into much newer code, where the live database can no longer answer what changed.
- **Bulk id minting/repair tooling** (`datapotter-id`) sharing the same implementation the processor already uses to mint ids one at a time on a failed build.
- Whatever `ALTER PROPERTY .. NAME` — if ArcadeDB ships one — turns out to need on this side; most of the migration recipe above exists only because it doesn't yet.

## Maven

```xml
<dependency>
    <groupId>io.github.datapotter</groupId>
    <artifactId>datapotter-arcadedbhelper</artifactId>
    <version>2.0</version>
</dependency>
```

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.github.datapotter</groupId>
        <artifactId>datapotter-arcadedbhelper-processor</artifactId>
        <version>2.0</version>
    </path>
</annotationProcessorPaths>
```

> Not yet on Maven Central — built and installed locally as `2.0-SNAPSHOT`. See [`core/README.md`](core/README.md) for version-compatibility notes across ArcadeDB releases.
