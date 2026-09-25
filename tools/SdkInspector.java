import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Inspects an AMap SDK jar: package layout, presence of native libs, whether key
 * classes expose native methods (which would need .so files not present in a jar),
 * and the signatures of the drawing-related APIs we intend to use.
 *
 * ASCII-only source: javac reads .java as GBK on this machine.
 * Usage: java SdkInspector <jar> <outFile> [className ...]
 */
public class SdkInspector {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        StringBuilder sb = new StringBuilder();

        // ---- 1) archive contents ----
        int classCount = 0;
        int nativeLibCount = 0;
        TreeSet<String> packages = new TreeSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.endsWith(".class")) {
                    classCount++;
                    int idx = n.lastIndexOf('/');
                    if (idx > 0) packages.add(n.substring(0, idx));
                }
                if (n.endsWith(".so") || n.endsWith(".dll") || n.contains("/jni/")) {
                    nativeLibCount++;
                    sb.append("NATIVE-ENTRY: ").append(n).append('\n');
                }
            }
        }
        sb.append("jar=").append(jar.toAbsolutePath()).append('\n');
        sb.append("sizeBytes=").append(Files.size(jar)).append('\n');
        sb.append("classCount=").append(classCount).append('\n');
        sb.append("nativeLibEntries=").append(nativeLibCount).append('\n');
        sb.append("packages:\n");
        for (String p : packages) sb.append("  ").append(p).append('\n');
        sb.append('\n');

        // ---- 2) inspect requested classes ----
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                SdkInspector.class.getClassLoader())) {
            for (int i = 2; i < args.length; i++) {
                String cn = args[i];
                sb.append("=== class ").append(cn).append('\n');
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    sb.append("  found, superclass=")
                      .append(c.getSuperclass() == null ? "-" : c.getSuperclass().getName())
                      .append('\n');
                    Method[] methods = c.getDeclaredMethods();
                    int natives = 0;
                    List<String> interesting = new ArrayList<>();
                    for (Method m : methods) {
                        if (Modifier.isNative(m.getModifiers())) {
                            natives++;
                            if (natives <= 5) sb.append("  NATIVE: ").append(m.getName()).append('\n');
                        }
                        String lower = m.getName().toLowerCase();
                        if (lower.contains("polygon") || lower.contains("marker")
                                || lower.contains("camera") || lower.contains("project")
                                || lower.contains("latlng") || lower.contains("position")) {
                            interesting.add("  api: " + m.getReturnType().getSimpleName()
                                    + " " + m.getName() + "(" + params(m) + ")");
                        }
                    }
                    sb.append("  declaredMethods=").append(methods.length)
                      .append(" nativeMethods=").append(natives).append('\n');
                    for (String s : interesting) sb.append(s).append('\n');
                } catch (Throwable t) {
                    sb.append("  NOT FOUND / ERROR: ").append(t.getClass().getSimpleName())
                      .append(": ").append(t.getMessage()).append('\n');
                }
                sb.append('\n');
            }
        }

        Files.writeString(out, sb.toString());
    }

    private static String params(Method m) {
        StringBuilder b = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ps[i].getSimpleName());
        }
        return b.toString();
    }
}
