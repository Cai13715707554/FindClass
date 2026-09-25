import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists public method signatures of selected classes in a jar.
 * Used to verify AMap SDK API names before writing code against them.
 * ASCII-only source: javac reads .java as GBK on this machine.
 * Usage: java ApiDump <jar> <outFile> <className> [className ...]
 */
public class ApiDump {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        StringBuilder sb = new StringBuilder();

        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                ApiDump.class.getClassLoader())) {
            for (int i = 2; i < args.length; i++) {
                String cn = args[i];
                sb.append("=== ").append(cn).append('\n');
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    for (java.lang.reflect.Field f : c.getFields()) {
                        if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("MAP_TYPE")) {
                            sb.append("  CONST ").append(f.getName()).append(" = ").append(f.get(null)).append('\n');
                        }
                    }
                    for (Method m : c.getMethods()) {
                        sb.append("  ").append(shortName(m.getReturnType().getName()))
                          .append(' ').append(m.getName())
                          .append('(').append(params(m)).append(")\n");
                    }
                } catch (Throwable t) {
                    sb.append("  NOT FOUND: ").append(t.getClass().getSimpleName())
                      .append(": ").append(t.getMessage()).append('\n');
                }
                sb.append('\n');
            }
        }
        Files.writeString(out, sb.toString());
    }

    private static String shortName(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static String params(Method m) {
        StringBuilder b = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) b.append(", ");
            b.append(shortName(ps[i].getName()));
        }
        return b.toString();
    }
}
