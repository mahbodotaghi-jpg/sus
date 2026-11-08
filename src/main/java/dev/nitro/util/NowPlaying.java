package dev.nitro.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the currently playing Spotify track from the local machine (no account login needed).
 * <ul>
 *   <li>Windows: System Media Transport Controls via PowerShell (title, artist, album, artwork, position)</li>
 *   <li>macOS: Spotify's AppleScript interface via osascript</li>
 *   <li>Linux: MPRIS via the {@code playerctl} command line tool</li>
 * </ul>
 * Polling runs on a background thread; the render thread only reads {@link #info()} and {@link #art()}.
 * This class deliberately has no Minecraft dependencies.
 */
public final class NowPlaying {
    public static final int ART_SIZE = 64;
    static final String SEP = "|||";

    static {
        System.setProperty("java.awt.headless", "true");
    }

    public static final class Info {
        public final String title, artist, album, artUrl;
        public final boolean playing;
        public final long posMs, lenMs, atNanos;
        final byte[] artBytes;

        Info(String title, String artist, String album, String artUrl, boolean playing, long posMs, long lenMs, byte[] artBytes) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.artUrl = artUrl == null ? "" : artUrl;
            this.playing = playing;
            this.posMs = Math.max(0, posMs);
            this.lenMs = Math.max(0, lenMs);
            this.artBytes = artBytes;
            this.atNanos = System.nanoTime();
        }

        public String key() {
            return title + "\u0001" + artist + "\u0001" + album;
        }

        /** Playback position extrapolated to now. */
        public long estimatedPosMs() {
            long p = posMs;
            if (playing) p += (System.nanoTime() - atNanos) / 1_000_000L;
            return lenMs > 0 ? Math.min(p, lenMs) : p;
        }
    }

    private volatile Info info;
    private volatile String status = "Starting...";
    private volatile BufferedImage art;
    private volatile String artKey = "";

    private ScheduledExecutorService pool;
    private ScheduledFuture<?> task;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private String loadingKey = "";

    public Info info() { return info; }

    public String status() { return status; }

    public BufferedImage art() { return art; }

    // ------------------------------------------------------------ lifecycle

    public synchronized void start(int intervalSeconds) {
        stop();
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nitro-nowplaying");
            t.setDaemon(true);
            return t;
        });
        task = pool.scheduleWithFixedDelay(this::pollSafe, 0, Math.max(1, intervalSeconds), TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (task != null) task.cancel(true);
        if (pool != null) pool.shutdownNow();
        task = null;
        pool = null;
        info = null;
        art = null;
        artKey = "";
        loadingKey = "";
    }

    private void pollSafe() {
        try {
            poll();
        } catch (Throwable t) {
            status = "Error: " + t.getClass().getSimpleName();
        }
    }

    private void poll() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Info i;
        if (os.contains("win")) {
            i = pollWindows(false);
            // Windows artwork arrives inside the script output; ask for it once per track.
            if (i != null && !i.key().equals(artKey) && i.artBytes == null) {
                Info withArt = pollWindows(true);
                if (withArt != null) i = withArt;
            }
        } else if (os.contains("mac")) {
            i = pollMac();
        } else {
            i = pollLinux();
        }
        info = i;
        if (i == null) return;
        status = i.playing ? "Playing" : "Paused";
        if (!i.key().equals(artKey) && !i.key().equals(loadingKey)) {
            loadingKey = i.key();
            try {
                BufferedImage img = null;
                if (i.artBytes != null) img = decode(i.artBytes);
                else if (!i.artUrl.isEmpty()) img = decode(download(i.artUrl));
                art = img;
            } catch (Exception e) {
                art = null;
            }
            artKey = i.key();
        }
    }

    // ------------------------------------------------------------ backends

    private Info pollLinux() {
        String fmt = "{{status}}" + SEP + "{{title}}" + SEP + "{{artist}}" + SEP + "{{album}}" + SEP
                + "{{mpris:artUrl}}" + SEP + "{{position}}" + SEP + "{{mpris:length}}";
        String out;
        try {
            out = run(List.of("playerctl", "-p", "spotify", "metadata", "--format", fmt), 4);
        } catch (IOException e) {
            status = "playerctl not found (install it to use Spotify HUD on Linux)";
            return null;
        }
        if (out == null || out.isBlank()) {
            status = "Spotify not running";
            return null;
        }
        return parseLinux(out);
    }

    static Info parseLinux(String out) {
        String[] p = out.strip().split("\\Q" + SEP + "\\E", -1);
        if (p.length < 7) return null;
        boolean playing = p[0].equalsIgnoreCase("Playing");
        // MPRIS uses microseconds.
        return new Info(p[1], p[2], p[3], p[4], playing, parseLong(p[5]) / 1000L, parseLong(p[6]) / 1000L, null);
    }

    private Info pollMac() {
        String script = String.join("\n",
                "tell application \"System Events\" to set isRunning to (exists process \"Spotify\")",
                "if isRunning then",
                "  tell application \"Spotify\"",
                "    set st to player state as string",
                "    set tn to name of current track",
                "    set ar to artist of current track",
                "    set al to album of current track",
                "    set au to artwork url of current track",
                "    set ps to player position as string",
                "    set du to duration of current track as string",
                "    return st & \"" + SEP + "\" & tn & \"" + SEP + "\" & ar & \"" + SEP + "\" & al & \"" + SEP + "\" & au & \"" + SEP + "\" & ps & \"" + SEP + "\" & du",
                "  end tell",
                "end if");
        String out;
        try {
            out = run(List.of("osascript", "-e", script), 5);
        } catch (IOException e) {
            status = "osascript unavailable";
            return null;
        }
        if (out == null || out.isBlank()) {
            status = "Spotify not running";
            return null;
        }
        return parseMac(out);
    }

    static Info parseMac(String out) {
        String[] p = out.strip().split("\\Q" + SEP + "\\E", -1);
        if (p.length < 7) return null;
        boolean playing = p[0].equalsIgnoreCase("playing");
        // position is seconds (possibly with a decimal comma), duration is milliseconds
        long pos = (long) (parseDouble(p[5]) * 1000.0);
        return new Info(p[1], p[2], p[3], p[4], playing, pos, (long) parseDouble(p[6]), null);
    }

    private static final String PS_SCRIPT = String.join("\n",
            "param([int]$Art = 0)",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
            "$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
            "function Await($op, $type) {",
            "  $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))",
            "  $task.Wait(-1) | Out-Null",
            "  $task.Result",
            "}",
            "[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]",
            "[void][Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]",
            "$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
            "$s = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1",
            "if (-not $s) { exit 0 }",
            "$p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
            "$pb = $s.GetPlaybackInfo()",
            "$tl = $s.GetTimelineProperties()",
            "Write-Output ('STATUS=' + $pb.PlaybackStatus)",
            "Write-Output ('TITLE=' + $p.Title)",
            "Write-Output ('ARTIST=' + $p.Artist)",
            "Write-Output ('ALBUM=' + $p.AlbumTitle)",
            "Write-Output ('POS=' + [int64]$tl.Position.TotalMilliseconds)",
            "Write-Output ('LEN=' + [int64]($tl.EndTime - $tl.StartTime).TotalMilliseconds)",
            "if ($Art -eq 1 -and $p.Thumbnail) {",
            "  $st = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])",
            "  $reader = New-Object Windows.Storage.Streams.DataReader($st)",
            "  [void](Await ($reader.LoadAsync([uint32]$st.Size)) ([uint32]))",
            "  $bytes = New-Object byte[] $st.Size",
            "  $reader.ReadBytes($bytes)",
            "  Write-Output ('ART=' + [Convert]::ToBase64String($bytes))",
            "}");

    private Path psFile;

    private Info pollWindows(boolean wantArt) {
        try {
            if (psFile == null || !Files.exists(psFile)) {
                psFile = Files.createTempFile("nitro-spotify", ".ps1");
                Files.writeString(psFile, PS_SCRIPT, StandardCharsets.UTF_8);
                psFile.toFile().deleteOnExit();
            }
            String out = run(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", psFile.toString(), "-Art", wantArt ? "1" : "0"), 12);
            if (out == null || out.isBlank()) {
                status = "Spotify not running";
                return null;
            }
            return parseWindows(out);
        } catch (IOException e) {
            status = "PowerShell unavailable";
            return null;
        }
    }

    static Info parseWindows(String out) {
        String status = "", title = "", artist = "", album = "";
        long pos = 0, len = 0;
        byte[] art = null;
        for (String line : out.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1).strip();
            switch (k) {
                case "STATUS" -> status = v;
                case "TITLE" -> title = v;
                case "ARTIST" -> artist = v;
                case "ALBUM" -> album = v;
                case "POS" -> pos = parseLong(v);
                case "LEN" -> len = parseLong(v);
                case "ART" -> {
                    try {
                        art = Base64.getDecoder().decode(v);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                default -> {
                }
            }
        }
        if (title.isEmpty() && artist.isEmpty()) return null;
        return new Info(title, artist, album, "", status.equalsIgnoreCase("Playing"), pos, len, art);
    }

    // ------------------------------------------------------------ helpers

    /** Runs a command, returns stdout (UTF-8) or null on non-zero exit / timeout. Throws if the executable is missing. */
    static String run(List<String> cmd, int timeoutSec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        proc.getOutputStream().close();
        final byte[][] holder = new byte[1][];
        Thread reader = new Thread(() -> {
            try {
                holder[0] = proc.getInputStream().readAllBytes();
            } catch (IOException ignored) {
            }
        }, "nitro-nowplaying-read");
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(timeoutSec * 1000L);
            if (reader.isAlive()) {
                proc.destroyForcibly();
                return null;
            }
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
        if (proc.exitValue() != 0 || holder[0] == null) return null;
        return new String(holder[0], StandardCharsets.UTF_8);
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        if (url.startsWith("file:")) return Files.readAllBytes(Path.of(URI.create(url)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode());
        return res.body();
    }

    /** Decode any ImageIO-supported image, centre-crop to a square and resample to ART_SIZE. */
    static BufferedImage decode(byte[] data) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
        if (src == null) throw new IOException("unsupported image");
        int side = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - side) / 2, sy = (src.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(ART_SIZE, ART_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, ART_SIZE, ART_SIZE, sx, sy, sx + side, sy + side, null);
        g.dispose();
        return out;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.strip().replace(',', '.'));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
