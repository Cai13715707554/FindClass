import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Downloads an artifact and reports what it actually is (.aar vs .jar, size).
 * ASCII-only source: javac reads .java as GBK on this machine.
 * Usage: java ArtifactProbe <outDir> <url> [<url> ...]
 */
public class ArtifactProbe {
    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args[0]);
        Files.createDirectories(outDir);
        StringBuilder log = new StringBuilder();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        for (int i = 1; i < args.length; i++) {
            String url = args[i];
            String name = url.substring(url.lastIndexOf('/') + 1);
            Path dest = outDir.resolve(name);
            log.append("=== ").append(name).append('\n').append(url).append('\n');
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                log.append("  HTTP ").append(resp.statusCode())
                   .append("  bytes=").append(resp.body().length).append('\n');
                if (resp.statusCode() == 200) {
                    Files.write(dest, resp.body());
                    byte[] b = resp.body();
                    String magic = (b.length >= 4)
                            ? String.format("%02X%02X%02X%02X", b[0], b[1], b[2], b[3])
                            : "?";
                    log.append("  magic=").append(magic)
                       .append("  (504B0304=zip/aar/jar)\n");
                    log.append("  saved=").append(dest.toAbsolutePath()).append('\n');
                }
            } catch (Throwable e) {
                log.append("  FAIL ").append(e.getClass().getSimpleName())
                   .append(": ").append(e.getMessage()).append('\n');
            }
            log.append('\n');
        }

        Files.writeString(outDir.resolve("probe-log.txt"), log.toString());
    }
}
