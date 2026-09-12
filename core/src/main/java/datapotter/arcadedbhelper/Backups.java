package datapotter.arcadedbhelper;

import com.arcadedb.database.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Taking a backup you can actually restore.
 *
 * <h3>Three things about {@code BACKUP DATABASE} that bite on Windows</h3>
 *
 * <p><b>The documented path argument does not work.</b> Every spelling of {@code <backup-file-url>} is
 * rejected, and the error names a directory the caller never supplied. The guard in
 * {@code BackupSettings} tests only {@code File.separator}, so a forward-slash path slips past it and is
 * then concatenated onto the default directory — {@code \backups\<db>\C:\Users\...} — which is not a
 * legal path. A backslash path trips the guard instead. Since the SQL statement always sets a default
 * directory, the argument can only ever be a bare filename. So this class does not pass one: it takes
 * the argument-less backup and moves the archive itself.
 *
 * <p><b>The default location is the DRIVE ROOT.</b> {@code /backups/<db>/} resolves to {@code C:\backups\...},
 * not the working directory the documentation implies. That is where this looks.
 *
 * <p><b>On 26.9.1 the archive can be unrestorable.</b> The dictionary entry is written under a
 * {@code <database-name>/} prefix while every other entry sits at the archive root, so plain extraction
 * yields a tree the engine refuses to open. The prefix is spurious rather than meaningful structure, so
 * {@link #extract} flattens it. Reported as ArcadeData/arcadedb#7586.
 *
 * <p>Needs {@code com.arcadedb:arcadedb-integration} on the RUNTIME classpath. There is no compile-time
 * dependency on it — the backup is issued as SQL — so its absence shows up only when a backup is taken,
 * which is why {@link #take} turns that into a message saying what to add.
 */
public final class Backups {

    private Backups() {}

    /**
     * Take a backup and put the archive where you asked for it, having checked it can be restored.
     *
     * <p>Reporting success for a file that exists is not enough: the whole point of a backup is the
     * restore, and an archive with a nested entry will not open. This fails rather than hand back
     * something that only looks like a backup.
     *
     * @param destination the file to end up with; parent directories are created
     * @return {@code destination}
     * @throws IllegalStateException if the archive cannot be found, or carries entries that will not
     *                               restore, or the integration jar is missing
     */
    public static Path take(Database db, Path destination) {
        try {
            db.command("sql", "BACKUP DATABASE");
        } catch (RuntimeException e) {
            var msg = String.valueOf(e.getMessage());
            if (msg.contains("backup libs not found"))
                throw new IllegalStateException(
                        "BACKUP DATABASE needs com.arcadedb:arcadedb-integration on the runtime classpath", e);
            throw e;
        }

        var archive = newestArchive(db.getName());
        if (archive == null)
            throw new IllegalStateException(
                    "BACKUP DATABASE reported success but no archive was found under " + defaultDir(db.getName()));

        try {
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            if (nestedEntries(archive).isEmpty())
                Files.move(archive, destination, StandardCopyOption.REPLACE_EXISTING);
            else {
                // Repair rather than refuse. Refusing would leave 26.9.1 unable to take a backup at all,
                // which is the version worth being on for the type-rename fix. The prefix is spurious, so
                // rewriting the archive flat makes it restorable by ANY tool, not just by Backups.extract.
                flattenInto(archive, destination);
                Files.delete(archive);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write backup archive to " + destination, e);
        }

        var stillNested = nestedEntries(destination);
        if (!stillNested.isEmpty())
            throw new IllegalStateException(
                    "Backup archive %s still carries nested entries after repair: %s".formatted(destination, stillNested));
        return destination;
    }

    /**
     * Entry names carrying a directory prefix — empty for an archive that will restore by extraction.
     *
     * <p>Worth calling on any archive taken by something other than {@link #take}, including one
     * restored from a colleague or a backup server.
     */
    public static List<String> nestedEntries(Path archive) {
        var nested = new ArrayList<String>();
        try (var zip = new ZipFile(archive.toFile())) {
            for (var entry : Collections.list(zip.entries()))
                if (!entry.isDirectory() && entry.getName().contains("/")) nested.add(entry.getName());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read backup archive " + archive, e);
        }
        return nested;
    }

    /**
     * Extract an archive into a directory that the engine can open, flattening any stray prefix.
     *
     * <p>A backup archive is flat by design — one directory of database files — so a nested entry is the
     * bug described on this class rather than structure worth keeping. Flattening is therefore safe, and
     * it is what makes an archive taken on 26.9.1 restorable at all.
     *
     * @return {@code target}
     */
    public static Path extract(Path archive, Path target) {
        try {
            Files.createDirectories(target);
            try (var zip = new ZipFile(archive.toFile())) {
                for (var entry : Collections.list(zip.entries())) {
                    if (entry.isDirectory()) continue;
                    var name = entry.getName();
                    var slash = name.lastIndexOf('/');
                    var out = target.resolve(slash < 0 ? name : name.substring(slash + 1));
                    try (var in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot extract backup archive " + archive, e);
        }
        return target;
    }

    /**
     * Copy an archive, moving every entry to the root — the repair for ArcadeData/arcadedb#7586.
     *
     * <p>Separate from {@link #extract} because the result is an ARCHIVE rather than a directory, so it
     * stays restorable by ArcadeDB's own tooling and by whoever receives it, not only by this class.
     */
    public static Path flattenInto(Path archive, Path destination) {
        try (var zip = new ZipFile(archive.toFile());
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(destination))) {
            for (var entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                var name = entry.getName();
                var slash = name.lastIndexOf('/');
                out.putNextEntry(new java.util.zip.ZipEntry(slash < 0 ? name : name.substring(slash + 1)));
                try (var in = zip.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot flatten backup archive " + archive, e);
        }
        return destination;
    }

    /** Where the argument-less {@code BACKUP DATABASE} actually writes: the drive root, not the cwd. */
    public static Path defaultDir(String databaseName) {
        return Path.of("/backups").toAbsolutePath().resolve(databaseName);
    }

    static Path newestArchive(String databaseName) {
        var dir = defaultDir(databaseName);
        if (!Files.isDirectory(dir)) return null;
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".zip"))
                    .max(Comparator.comparing(Backups::modifiedAt))
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }

    static long modifiedAt(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
