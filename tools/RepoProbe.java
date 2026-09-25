import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Diagnostic tool: probes AMap SDK Maven repositories for reachability.
 * Not part of the app build. Writes results to a file to avoid relying on stdio.
 * ASCII-only source on purpose: javac reads .java as GBK on this machine.
 */
public class RepoProbe {
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        StringBuilder sb = new StringBuilder();

        String[][] targets = {
            {"map2d-pom", "https://maven.aliyun.com/repository/public/com/amap/api/map2d/6.0.0/map2d-6.0.0.pom"},
            {"map3d-pom", "https://maven.aliyun.com/repository/public/com/amap/api/3dmap/10.0.600/3dmap-10.0.600.pom"},
            {"location-pom", "https://maven.aliyun.com/repository/public/com/amap/api/location/6.4.5/location-6.4.5.pom"},
            {"amap-group-index", "https://maven.aliyun.com/repository/public/com/amap/api/"},
            {"amap-official-repo", "https://maven.amap.com/repository/maven/com/amap/api/map2d/6.0.0/map2d-6.0.0.pom"},
            {"central-mirror", "https://repo1.maven.org/maven2/com/amap/api/map2d/6.0.0/map2d-6.0.0.pom"},
            {"aliyun-public-root", "https://maven.aliyun.com/repository/public/"},
        };

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        for (String[] t : targets) {
            sb.append("[").append(t[0]).append("] ").append(t[1]).append('\n');
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(t[1]))
                        .timeout(Duration.ofSeconds(25))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                sb.append("  HTTP ").append(resp.statusCode())
                  .append("  len=").append(resp.body().length()).append('\n');
                if (resp.statusCode() == 200 && resp.body().length() < 3000) {
                    sb.append("  body: ").append(resp.body().replace('\n', ' ')).append('\n');
                }
            } catch (Throwable e) {
                sb.append("  FAIL ").append(e.getClass().getSimpleName())
                  .append(": ").append(e.getMessage()).append('\n');
            }
            sb.append('\n');
        }

        Files.writeString(out, sb.toString());
    }
}
