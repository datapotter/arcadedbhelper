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
            .factory(Person::new)            // zero-reflection factory, so reads need no X::new
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

### Enum-valued properties

An enum field, or a `List` of one, is stored as `STRING` / a `LIST` of them, in whichever form the enum declares — see [Enums](../README.md#enums--asuuid--asname) in the root README for `@AsUuid` / `@AsName`.

```java
@ArcadeData
public final class Ticket extends Ticket_A {
    Outcome outcome;                 // -> STRING, holding Outcome.uuid()
    List<Severity> severities;       // -> LIST of Severity.name()
}
```

ArcadeDB has no notion of a Java enum, so this backend adds one rule of its own: **an enum whose type carries neither annotation cannot be a property here**, and says so at compile time rather than storing a `toString()` nobody can read back. The substitution happens on every write path and in the query DSL alike, so `eq($outcome, Outcome.FAVOURABLE)` and `whereEq($caseCode, CaseCode.BETA)` — including as an indexed lookup key — compare against the stored string, and a value in storage matching no constant resolves to `null` (or, in a list, is dropped) instead of throwing.

## Typed reads and writes

Both sides of the API are typed, so neither a type name nor a field name appears as a string, and field and value are bound by the generic signature — `eq($size, "big")` against a `Long` column is a compile error, not a query that silently matches nothing.

```java
import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

// READ — lazy. A Query IS an Iterable: nothing is buffered, and break stops the scan.
for (Person p : query(db, Person.TYPEDEF).eq($city, "Novosibirsk").orderByAsc($name)) { ... }

Person one = query(db, Person.TYPEDEF).eq($email, email).firstOrNull();
long adults = query(db, Person.TYPEDEF).ge($age, 18).count();

// WRITE — name the key, name the fields you mean, touch nothing else.
db.transaction(() -> upsert(db, Person.TYPEDEF).key($email, email).set($age, 30).save());
```

Operators: `eq neq lt le gt ge like ilike in between isNull isNotNull`; conditions AND by default, `.or()` switches the next one. Terminals: for-each, `stream()`, `firstOrNull()`, `first()`, `list()` (the one eager form), `count()`, `exists()`, and `documents()` / `vertices()` / `firstVertex()` for the engine's raw records — the right choice for a bulk pass that reads two fields out of hundreds of thousands of rows.

Two limits come from the engine and are stated rather than hidden: there are **no parentheses and no `NOT`** (precedence is SQL's, so `(a OR b) AND (c OR d)` needs a SQL string), and **nested embedded fields cannot be filtered**. For the second, `Query` throws with the working alternatives named, instead of passing the engine's silent empty result through.

`Upsert` complements the whole-object instance DSL rather than replacing it: use `in(db)…upsert()` when the object *is* the truth, and `Upsert` when you hold a possibly-stale snapshot and mean to change specific fields. `set(f, null)` erases; `setIfPresent(f, null)` leaves the existing value alone.

## Deleting, and the index trap underneath it

`Delete` is the third verb. It reads like the other two, and it exists because the obvious alternative is a trap.

```java
import static datapotter.arcadedbhelper.Query.query;

long gone = Delete.matching(query(db, Ref.TYPEDEF).eq($projectId, pid));
```

**Do not remove rows in bulk with `db.command("sql", "DELETE FROM X WHERE …")`.** At around 370,000 rows it left the LSM indexes holding entries for records that no longer existed. Nothing failed at the time. Every *later* indexed read then threw `RecordNotFoundException` from the middle of an iteration — so queries that had worked for months became stack traces, and one that should have found 2,543 rows found none. The damage is also self-perpetuating: an orphaned entry has no record behind it, so a delete cannot find it and a re-insert writes new entries beside it. Deleting and rewriting every row of the type returned byte-identical orphan counts.

`Delete` loads each record and removes it through `Database.deleteRecord`, which is what lets the engine find and remove the index entries — the load is the point, not overhead.

### When you are moving a lot of rows, take the indexes down

Index maintenance is paid once per row per index, and it dominates everything else. Measured on 50,000 rows of a five-index type:

```
plain SQL delete, indexes live       142 s
Delete.matching, indexes live         98 s
drop indexes → delete → rebuild      2.9 s      (drop 18 ms, rebuild 110 ms)
```

```java
Indexes.duringBulkChange(db, List.of(Ref.TYPEDEF, Decl.TYPEDEF), () -> {
    Delete.matching(query(db, Ref.TYPEDEF).eq($projectId, pid));
    writeEverythingAgain();                       // inserts skip index maintenance too
});                                               // indexes rebuilt once, in a finally
```

The rebuild is also what makes the result *correct*, since every entry is then derived from a record that exists. While the window is open, queries on those types fall back to full scans and **unique constraints are not enforced** — so this is for a caller that serialises access, and it is the wrong tool for a handful of rows.

### Repair

```java
Indexes.rebuild(db, Ref.TYPEDEF, Decl.TYPEDEF);   // the only thing that clears an orphaned entry
Indexes.orphans(db, "Ref");                       // count the damage without materialising rows
```

And for a reader that would rather answer than throw:

```java
var q = query(db, Ref.TYPEDEF).eq($projectId, pid).skippingUnreadable();
var rows = q.list();
if (q.unreadableSkipped() > 0) warn(q.unreadableSkipped() + " rows unreadable — run the repair");
```

It deliberately does not log for you. A short answer presented as a complete one is worse than the crash it replaced. Note that `count()` and `exists()` are answered by the engine from the index and so still include orphans — that disagreement is itself a useful signal.

## References & graph (`LINK`)

A DTO-typed field **embeds a copy**; a field wrapped in `Link<T>` stores only a **reference** — the target's RID — and the target is resolved separately, never by hidden I/O. The distinction is self-documenting and falls out of the type:

```java
@ArcadeData
public final class Invoice extends Invoice_A {
    String invoiceId;
    Link<Customer>          customer;   // 1:1  → schema LINK
    LinkList<LineItem>      items;      // 1:n  → LIST of links
    LinkMap<String,Account> ledgers;    // n:n  → MAP of links

    Address billingAddress;             // a DTO field still embeds by value (unchanged)
}
```

**One rule for reading: a method that takes `db` performs the fetch; one that doesn't is a pure carrier and never touches the database.**

```java
inv.customer();                  // Link<Customer> — carrier, no I/O
inv.customer().rid();            // "#12:3"        — the identity
inv.customer().get();            // Customer       — only if the query projected it (else throws)
inv.customer().resolve(db);      // Customer       — the explicit fetch
inv.items().resolveAll(db);      // List<LineItem> — batch fetch
```

Resolution instantiates targets through a generated `Customer::new` factory — **zero reflection**, so links are GraalVM-native-safe like the rest of DataHelper. Setting them is fluent — by object, rid, or collection:

```java
new Invoice().invoiceId("INV1")
    .customer(cust)              // by object (reads cust.$rid())
    .customer("#12:3")           // by rid
    .items(List.of(a, b))        // List<Target>  → LinkList
    .ledgers(Map.of("main", acc));// Map<K,Target> → LinkMap
```

### Query-shaped resolution

Let the database do the join — a nested projection comes back already resolved:

```java
try (var rs = db.query("sql", "SELECT *, customer:{*} FROM Invoice WHERE invoiceId = ?", "INV1")) {
    var inv = new Invoice().fromResult(rs.next());
    inv.customer().get().name();  // resolved straight from the row — no extra lookup
}
```

### Self-identity, edge endpoints, vertex adjacency

Every loaded record carries its own read-only `$rid()`. Graph records (declared with `@ArcadeData(type = VERTEX | EDGE)`) extend the same carrier vocabulary:

```java
loaded.$rid();                              // "#12:3" — read-only, bound on load

// EDGE — exactly one source + one target endpoint (single Link each)
knows.$out();                               // Link<?> — the source-vertex reference
knows.$out().resolve(db, Person::new);      // Person  — fetch it (type supplied as a factory)

// VERTEX — adjacency over edge types (a LinkList of neighbours), resolved
bob.$out (db, Person::new, Knows.class);    // LinkList<Person> — out-neighbours
bob.$in  (db, Person::new, Knows.class);    // in-neighbours
bob.$both(db, Person::new, Knows.class);    // both directions
```

Edge types are passed as their generated type class (`Knows.class`), not strings — refactor-safe. There is no lazy faulting anywhere: `get()` reads what the query projected, `resolve(db)`/`$out(db,…)` are the only places that hit the database.

Those address by `$rid()`, so they fetch the vertex behind the snapshot. When you already hold the `Vertex` — which is what a lookup by business key gives you — use `Traverse` instead and skip the round-trip:

```java
Vertex bobV = query(db, Person.TYPEDEF).eq($email, "bob@x").firstVertex();
Traverse.out(bobV, Person::new, Knows.class);   // also .in / .both / .adjacent(v, DIRECTION, ...)
```

Same traversal, same results; it just avoids building a DTO purely to read its RID and then re-fetching the vertex you started from. Over a subtree walk that is one redundant record fetch per node.

## Schema evolution: identity, detection, and a safe migration recipe

Every code-first schema tool diffs by name, which makes a rename indistinguishable from a delete plus an add. Give a type or property a stable identity instead, and that category of failure stops existing.

### Declaring identity

```java
@ArcadeData(id = "aK3f_9")                      // type identity
public final class Person extends Person_A {
    @P("o3KcQR") String name;                   // field identity
    String email;                                // unidentified — fine, degrades to name-based behaviour
}
```

Add `requireIds = true` to the annotation once a type is meant to be fully identified — it turns a missing `@P` on *any* field into a build failure naming the id to paste, rather than a silent partial-adoption gap.

Six base64url characters, five random plus one checksum character, for both `@P` and `@ArcadeData(id=...)`. **Minted, never hand-authored**: omit `@P` on a field under `requireIds = true` and the build fails with a freshly generated id in the message, ready to paste. The checksum means a hand-picked six characters passes only 1 time in 64, and any single-character typo of a real id fails the same way — at compile time, instead of silently orphaning a column at migration time.

**Identity is optional at every granularity, and that's a requirement, not a gap.** A schema still being conceived shouldn't have to commit to permanence it hasn't earned — annotate everything, one type, one field, or nothing. Matching runs in a fixed order: by id first, then whatever's left by name, then report the remainder as unmatched. Nothing about partial adoption is a half-state to warn about.

### What happens automatically at init

`InitDoc.initDocTypes` reads the current schema — by id, then by name — **before** creating or renaming anything, which is what makes detection possible at all (an eager `existsType` check would have already created the duplicate a rename must not produce):

- **Type matched by id, different name → renamed outright**, through `Rename.type` (see below) rather than a bare engine rename.
- **Property matched by id, different name → detected, not renamed.** ArcadeDB's own `ALTER PROPERTY .. NAME` / `Property.rename()` is schema-only and lazy — see the recipe below for why applying this still needs a real data-migration step, not just that one call. Collected into a returned `MigrationPlan` instead of happening implicitly; the property keeps its current name until that plan is applied.
- **Recorded id no longer claimed by any declared field → marked orphaned, never dropped.** An id vanishing from source is ambiguous — a deleted field, or one merely commented out mid-refactor — and guessing wrong destroys data. A deliberate drop is the only way to actually remove one.
- **A name matches but the recorded id differs → refused with an exception naming both ids.** This is the signature of an edited or mistyped id, not a rename; restoring the original id or confirming the change deliberately are the only ways forward.
- **A matched id also implies a type change → refused, not guessed.** `String` → `Integer` under one id is a data migration, not a rename.

### Applying a detected property rename

```java
MigrationPlan plan = InitDoc.initDocTypes(db, Person.TYPEDEF, Order.TYPEDEF);
if (!plan.isEmpty()) {
    plan.print();                    // read-only — nothing above this line has touched anything
    plan.apply(db, backupPath);       // backs up, then applies every pending rename
}
```

`apply` is the **only** method in this design that rewrites data. It backs up first, then runs a six-step recipe per rename, journalling each step beside the database before the next one runs so a crash mid-migration resumes rather than restarts or double-applies:

1. Drop every index standing on the *old* property — `Property.rename()` refuses outright while one does, same as `DROP PROPERTY`.
2. `old.rename(new)` — the schema swap, via ArcadeDB's own primitive (ArcadeData/arcadedb#7589). One call carries across type, `ofType`, every constraint, and every custom value — including `readonly`, which is a trap covered below.
3. `UPDATE <T> SET <new> = <old>` — transactional; DML through `db.command` needs an explicit one.
4. `UPDATE <T> REMOVE <old>` — must run **after** step 3, or the column is nulled before anything copies it.
5. Restore `readonly` on the new property, if the old one had it set.
6. Rebuild the indexes captured in step 1, now naming the new property.

Every step is a no-op if its effect is already visible in the schema, so resuming from a stale journal entry is safe — what actually matters is that steps 3 and 4 can never run out of order, which the fixed sequence makes impossible to do by accident.

**Why the engine's own rename doesn't collapse this to one call, even though it now exists.** `Property.rename()` is schema-metadata-only and lazy: a row written before the rename keeps answering under the *old* field name until it's next written, and only a fresh write lands under the new one. So it makes the schema half of a rename free and complete — no more hand-copying a constraint list that used to silently drop any custom value other than this library's own id — but the data movement in steps 3-4 above is still ours to do. A separate, open proposal upstream, [#7648](https://github.com/ArcadeData/arcadedb/issues/7648), asks for an *eager* variant that rewrites every record in the engine itself; if it ships, steps 3-4 collapse into one engine statement.

### Renaming a type doesn't have the same problem — except it did

`DocumentType.rename` exists in the engine, so a type rename is O(1) in principle. In practice, renaming a type on ArcadeDB up to and including 26.8.1 leaves the index *file* under the old name while the schema goes on recording it under the new one — correct in memory, and then at the next reopen the loader can't find the file and drops it with a single `WARNI` line. That's a dropped constraint, not a slow query: a UNIQUE index over five rows, renamed and reopened, then accepted a duplicate insert and went to six rows, with nothing reported. It can't be repaired afterwards, either — recreating the index fails because the schema still believes the old entry exists.

`Rename.type(db, from, to)` is the fix: capture every index definition, drop the indexes while the schema and the files still agree, rename, then rebuild against the new name — the rebuild runs in a `finally`, so a rename that throws doesn't leave the type bare. Fixed upstream in 26.9.1, and this wrapper stays in place regardless — it costs one index rebuild either way, and a library doesn't get to assume its callers are on the newest release.

### Known gaps, stated rather than hidden

- The `UPDATE .. BATCH` size is a fixed default (1000), not yet a per-call tuning knob.
- Narrowing a property's type (`int → short`) isn't handled — it would need a data scan to prove the existing values fit, which is planned but not built.
- There is no bulk id-minting CLI yet; minting today happens one field at a time, via a failed build naming the id to paste.
- Nothing here is a schema history table — a run doesn't yet record what the schema looked like before it changed, which matters most for a backup restored into much newer code.

## Maven

```xml
<dependency>
    <groupId>io.github.datapotter</groupId>
    <artifactId>datapotter-arcadedbhelper</artifactId>
    <version>2.0</version>
</dependency>
```

> **2.0 is not yet on Maven Central** — built and installed locally as `2.0-SNAPSHOT`. From 2.0 the two ArcadeDB modules share one version, built by `arcadedbhelper/pom.xml`, and consume the datahelper core through a single `${datahelper.version}` property. They are a **separate reactor** from datahelper on purpose: this is one backend over that core, the dependency has only ever run one way, and the two lines moved independently through 1.x (this reached 1.8 while `datahelper-annotations` reached 1.2). They happen to start aligned at 2.0 because this is the release that consumes datahelper 2.0; they are free to diverge afterwards.

> **What 2.0 changes here.** An enum-valued field's symbol is now `EnumField`/`EnumListField` rather than a plain `Field`, and it carries its own resolver — so a generated entity no longer holds a private `Map` plus a builder method per enum field, and `resolveEnumFromStorage` is uniformly `case "x" -> $x.fromStorage(v)`. The stored shape is unchanged, so **no data migration**: an enum is still one `STRING` column and a list of them still a `LIST` of the same strings. Internally the three places that dispatch over the sealed `Field_I` are now exhaustive `switch`es rather than `instanceof` chains, so a descriptor added to that family fails to compile until each site decides what it means.

> **Use 1.2 or later with ArcadeDB 26.x.** 26.x removed the `RID(BasicDatabase, …)` constructors in favour of static `RID.create(…)`. 1.0 still calls the removed form, so it compiles against 26.x and then throws `NoSuchMethodError` the moment the line runs — which silently disables every `Link<T>` resolve, every `Link`/`LinkList`/`LinkMap` write, and all of `$out`/`$in`/`$both`, while the schema and every scalar field keep working. 1.1 fixed that and made record creation dispatch to `newVertex` for `@ArcadeData(type = VERTEX)` types (so the instance DSL covers vertices); 1.2 adds the typed field-level `Upsert`; 1.3 adds `Query` (typed, lazy reads) and `Traverse` (adjacency from a held `Vertex`), plus `TypeDef.factory(X::new)`; 1.4 adds `Delete`, `Indexes` (bulk-change window and index repair) and `Query.skippingUnreadable()` — see the deleting section above, which is worth reading before removing rows in bulk. From 2.0 the processor artifact below carries the same version as this one.

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.github.datapotter</groupId>
        <artifactId>datapotter-arcadedbhelper-processor</artifactId>
        <version>2.0</version>
    </path>
</annotationProcessorPaths>
```

> **Two-compilation note:** the sealed `_A` parent is generated from the annotated class, which then `extends` it — a circular dependency that can need two passes on a first build. `mvn clean install` handles it; don't assume the code is broken if the very first compile complains about `Xxx_A`.

## Full tutorial

This README is the quick orientation. The complete, worked guide — VERTEX/EDGE graph modelling, links, embedded types, the database-service pattern, all CRUD operations, the type-safe vs string-based query API, transactions, and database-lifecycle do's and don'ts — lives in:

`project-journals/aracde_db_context/arcade-db-working-examples-2026-07-31.md`
