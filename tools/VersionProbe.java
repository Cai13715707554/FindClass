import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists available versions of AMap artifacts via maven-metadata.xml.
 * ASCII-only source: javac reads .java as GBK on this machine.
 * Usage: java VersionProbe <outFile>
 */
public class VersionProbe {
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        StringBuilder sb = new StringBuilder();

        String[] artifacts = {
            "https://repo1.maven.org/maven2/com/amap/api/map2d/maven-metadata.xml",
            "https://repo1.maven.org/maven2/com/amap/api/3dmap/maven-metadata.xml",
            "https://repo1.maven.org/maven2/com/amap/api/location/maven-metadata.xml",
            "https://repo1.maven.org/maven2/com/amap/api/search/maven-metadata.xml",
            "https://maven.aliyun.com/repository/public/com/amap/api/map2d/maven-metadata.xml",
        };

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        Pattern versionTag = Pattern.compile("<version>([^<]+)</version>");

        for (String url : artifacts) {
            sb.append("=== ").append(url).append('\n');
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                sb.append("  HTTP ").append(resp.statusCode()).append('\n');
                if (resp.statusCode() == 200) {
                    Matcher m = versionTag.matcher(resp.body());
                    StringBuilder versions = new StringBuilder();
                    int n = 0;
                    while (m.find()) {
                        if (n++ > 0) versions.append(", ");
                        versions.append(m.group(1));
                    }
                    sb.append("  versions(").append(n).append("): ").append(versions).append('\n');
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
