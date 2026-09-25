import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists class entries in a jar whose name matches a substring.
 * ASCII-only source: javac reads .java as GBK on this machine.
 * Usage: java JarList <jar> <outFile> <substring>
 */
public class JarList {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        String needle = args[2].toLowerCase();
        List<String> hits = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".class") && n.toLowerCase().contains(needle)) {
                    hits.add(n.replace('/', '.'));
                }
            }
        }
        Collections.sort(hits);
        StringBuilder sb = new StringBuilder();
        sb.append("matches for '").append(needle).append("': ").append(hits.size()).append('\n');
        for (String h : hits) sb.append("  ").append(h).append('\n');
        Files.writeString(Paths.get(args[1]), sb.toString());
    }
}
