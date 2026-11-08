package dev.nitro.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics for the current track, fetched in the background from the public LRCLIB service
 * (https://lrclib.net, no account needed). Results are only held in memory for the current track.
 */
public final class Lyrics {
    public enum State { IDLE, LOADING, FOUND, NONE }

    public static final class Line {
        public final long ms;
        public final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nitro-lyrics");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.IDLE;
    private volatile List<Line> lines = List.of();
    private String key = "";
    private long gen;

    public State state() {
        return state;
    }

    public List<Line> lines() {
        return lines;
    }

    public synchronized void clear() {
        key = "";
        gen++;
        lines = List.of();
        state = State.IDLE;
    }

    /** Starts loading lyrics when the track changed; cheap to call every frame. */
    public synchronized void request(NowPlaying.Info i) {
        if (i == null || (i.title.isEmpty() && i.artist.isEmpty())) return;
        String k = i.key() + "\u0001" + i.lenMs;
        if (k.equals(key)) return;
        key = k;
        final long g = ++gen;
        lines = List.of();
        state = State.LOADING;
        final String title = i.title, artist = i.artist, album = i.album;
        final long lenMs = i.lenMs;
        EXEC.submit(() -> load(g, title, artist, album, lenMs));
    }

    /** Index of the line that is current at the given playback position, or -1 before the first line. */
    public int indexAt(long posMs) {
        List<Line> l = lines;
        int lo = 0, hi = l.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).ms <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    // ------------------------------------------------------------------ loading

    private void load(long g, String title, String artist, String album, long lenMs) {
        List<Line> result = List.of();
        try {
            String synced = exact(title, artist, album, lenMs);
            if (synced == null || synced.isBlank()) synced = search(title, artist, lenMs);
            if (synced != null && !synced.isBlank()) result = parse(synced);
        } catch (Exception e) {
            System.err.println("[NITRO] Lyrics lookup failed: " + e);
        }
        synchronized (this) {
            if (g != gen) return; // track changed meanwhile
            lines = result;
            state = result.isEmpty() ? State.NONE : State.FOUND;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "NITRO-client (Fabric mod)")
                .timeout(Duration.ofSeconds(8))
                .GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String exact(String title, String artist, String album, long lenMs) throws Exception {
        StringBuilder u = new StringBuilder("https://lrclib.net/api/get?track_name=").append(enc(title))
                .append("&artist_name=").append(enc(artist));
        if (!album.isEmpty()) u.append("&album_name=").append(enc(album));
        if (lenMs > 0) u.append("&duration=").append(Math.round(lenMs / 1000.0));
        String body = get(u.toString());
        if (body == null) return null;
        return str(JsonParser.parseString(body).getAsJsonObject(), "syncedLyrics");
    }

    private static String search(String title, String artist, long lenMs) throws Exception {
        String body = get("https://lrclib.net/api/search?q=" + enc((title + " " + artist).trim()));
        if (body == null) return null;
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        List<JsonObject> ok = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            String s = str(o, "syncedLyrics");
            if (s != null && !s.isBlank()) ok.add(o);
        }
        if (ok.isEmpty()) return null;
        if (lenMs > 0) {
            double want = lenMs / 1000.0;
            ok.sort(Comparator.comparingDouble(o -> {
                JsonElement d = o.get("duration");
                return d == null || d.isJsonNull() ? 9999.0 : Math.abs(d.getAsDouble() - want);
            }));
            JsonElement d = ok.get(0).get("duration");
            if (d != null && !d.isJsonNull() && Math.abs(d.getAsDouble() - want) > 6.0) return null;
        }
        return str(ok.get(0), "syncedLyrics");
    }

    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1)), sec = Long.parseLong(m.group(2));
                long frac = 0;
                String f = m.group(3);
                if (f != null) {
                    long v = Long.parseLong(f);
                    frac = f.length() == 1 ? v * 100 : f.length() == 2 ? v * 10 : v;
                }
                stamps.add(min * 60_000L + sec * 1000L + frac);
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : stamps) out.add(new Line(t, text));
        }
        out.sort(Comparator.comparingLong(l -> l.ms));
        return out;
    }
}
