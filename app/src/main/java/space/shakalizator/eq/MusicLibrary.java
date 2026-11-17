package space.shakalizator.eq;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class MusicLibrary {
    record Song(Uri uri, String title, String artist, String album, long duration, int disc, int number) {}
    record Album(String title, String artist, List<Song> songs) {}
    private record Folder(String id, String name) {}
    private record AlbumKey(String title, String artist, String folderId) {}

    static final Song DEMO = new Song(null, "Shakal Loop", "ShakalEQ", "Демо", 12468, 1, 1);
    private static final String[] COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
    };

    static List<Album> scan(Context context, Uri tree) throws IOException, InterruptedException {
        var pending = new ArrayDeque<Folder>();
        var visited = new HashSet<String>();
        var albums = new LinkedHashMap<AlbumKey, List<Song>>();
        String root = DocumentsContract.getTreeDocumentId(tree);
        String rootName = "Без альбома";
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, root);
        try (Cursor row = context.getContentResolver().query(rootDocument,
                new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst() && row.getString(0) != null) rootName = row.getString(0);
        }
        pending.add(new Folder(root, rootName));
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Folder folder = pending.removeFirst();
            if (!visited.add(folder.id())) continue;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.id());
            try (Cursor rows = context.getContentResolver().query(children, COLUMNS, null, null, null)) {
                if (rows == null) throw new IOException("Источник не вернул список файлов");
                while (rows.moveToNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    String id = rows.getString(0);
                    String name = rows.getString(1);
                    String mime = rows.getString(2);
                    if (id == null || name == null) continue;
                    if (Document.MIME_TYPE_DIR.equals(mime)) {
                        pending.add(new Folder(id, name));
                    } else if (isAudio(name, mime)) {
                        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        read(context, uri, name, folder, albums);
                    }
                }
            }
        }
        var result = new ArrayList<Album>();
        Comparator<Song> order = Comparator.comparingInt(Song::disc)
                .thenComparingInt(Song::number).thenComparing(Song::title, String.CASE_INSENSITIVE_ORDER);
        for (var entry : albums.entrySet()) {
            entry.getValue().sort(order);
            result.add(new Album(entry.getKey().title(), entry.getKey().artist(),
                    java.util.Collections.unmodifiableList(entry.getValue())));
        }
        result.sort(Comparator.comparing(Album::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Album::artist, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static Song single(Context context, Uri uri, String name) {
        return read(context, uri, name, new Folder("", "Аудиофайл"), new LinkedHashMap<>());
    }

    private static Song read(Context context, Uri uri, String filename, Folder folder,
                             LinkedHashMap<AlbumKey, List<Song>> albums) {
        var meta = new MediaMetadataRetriever();
        int dot = filename.lastIndexOf('.');
        String title = dot > 0 ? filename.substring(0, dot) : filename;
        String artist = "";
        String album = folder.name();
        String albumArtist = "";
        int disc = 1;
        int number = number(title.split("[ ._-]", 2)[0], Integer.MAX_VALUE);
        long duration = 0;
        try {
            meta.setDataSource(context, uri);
            title = value(meta, MediaMetadataRetriever.METADATA_KEY_TITLE, title);
            artist = value(meta, MediaMetadataRetriever.METADATA_KEY_ARTIST, artist);
            album = value(meta, MediaMetadataRetriever.METADATA_KEY_ALBUM, album);
            albumArtist = value(meta, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "");
            duration = Long.parseLong(value(meta, MediaMetadataRetriever.METADATA_KEY_DURATION, "0"));
            disc = number(value(meta, MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER, ""), 1);
            number = number(value(meta, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, ""), number);
        } catch (RuntimeException ignored) {
            // A malformed tag or unavailable decoder still leaves a filename and folder fallback.
        } finally {
            try { meta.release(); } catch (IOException | RuntimeException ignored) {}
        }
        Song song = new Song(uri, title, artist, album, Math.max(0, duration), disc, number);
        AlbumKey key = new AlbumKey(album, albumArtist, albumArtist.isEmpty() ? folder.id() : "");
        albums.computeIfAbsent(key, unused -> new ArrayList<>()).add(song);
        return song;
    }

    private static String value(MediaMetadataRetriever meta, int key, String fallback) {
        String value = meta.extractMetadata(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int number(String value, int fallback) {
        try {
            int number = Integer.parseInt(value.split("/", 2)[0].trim());
            return number > 0 ? number : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|flac|wav|m4a|aac|ogg|opus|oga|aiff|amr|wma|mka)$");
    }
}
