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
for (Person p : query(db, Person.TYPEDEF).eq($city, "Chennai").orderByAsc($name)) { ... }

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

## Maven

```xml
<dependency>
    <groupId>io.github.datapotter</groupId>
    <artifactId>datapotter-arcadedbhelper</artifactId>
    <version>1.4</version>
</dependency>
```

> **Use 1.2 or later with ArcadeDB 26.x.** 26.x removed the `RID(BasicDatabase, …)` constructors in favour of static `RID.create(…)`. 1.0 still calls the removed form, so it compiles against 26.x and then throws `NoSuchMethodError` the moment the line runs — which silently disables every `Link<T>` resolve, every `Link`/`LinkList`/`LinkMap` write, and all of `$out`/`$in`/`$both`, while the schema and every scalar field keep working. 1.1 fixed that and made record creation dispatch to `newVertex` for `@ArcadeData(type = VERTEX)` types (so the instance DSL covers vertices); 1.2 adds the typed field-level `Upsert`; 1.3 adds `Query` (typed, lazy reads) and `Traverse` (adjacency from a held `Vertex`), plus `TypeDef.factory(X::new)`; 1.4 adds `Delete`, `Indexes` (bulk-change window and index repair) and `Query.skippingUnreadable()` — see the deleting section above, which is worth reading before removing rows in bulk. The processor artifact below stays at `1.0`.

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.github.datapotter</groupId>
        <artifactId>datapotter-arcadedbhelper-processor</artifactId>
        <version>1.0</version>
    </path>
</annotationProcessorPaths>
```

> **Two-compilation note:** the sealed `_A` parent is generated from the annotated class, which then `extends` it — a circular dependency that can need two passes on a first build. `mvn clean install` handles it; don't assume the code is broken if the very first compile complains about `Xxx_A`.

## Full tutorial

This README is the quick orientation. The complete, worked guide — VERTEX/EDGE graph modelling, links, embedded types, the database-service pattern, all CRUD operations, the type-safe vs string-based query API, transactions, and database-lifecycle do's and don'ts — lives in:

`project-journals/aracde_db_context/arcade-db-working-examples-2026-07-31.md`
