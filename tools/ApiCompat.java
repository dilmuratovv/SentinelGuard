// Binary-compatibility probe used by tools/api_compat.py (run with: java tools/ApiCompat.java <refs.tsv> <classpath>).
// Each line of refs.tsv names a class, method or field the plugin links against; the probe checks that it
// exists, with the same descriptor, in the API on the classpath. Exit status 1 when anything is missing.
import java.io.*; import java.lang.invoke.MethodType; import java.lang.reflect.*; import java.net.*; import java.nio.file.*; import java.util.*;
public class ApiCompat {
  static boolean hasMethod(Class<?> c, String name, String desc, Set<Class<?>> seen) {
    if (c == null || !seen.add(c)) return false;
    try {
      for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) return true;
      if (name.equals("<init>")) for (Constructor<?> k : c.getDeclaredConstructors()) if (MethodType.methodType(void.class, k.getParameterTypes()).toMethodDescriptorString().equals(desc)) return true;
    } catch (Throwable t) { throw new RuntimeException("unresolved:" + t); }
    if (hasMethod(c.getSuperclass(), name, desc, seen)) return true;
    for (Class<?> i : c.getInterfaces()) if (hasMethod(i, name, desc, seen)) return true;
    return false;
  }
  static boolean hasField(Class<?> c, String name, Set<Class<?>> seen) {
    if (c == null || !seen.add(c)) return false;
    for (Field f : c.getDeclaredFields()) if (f.getName().equals(name)) return true;
    if (hasField(c.getSuperclass(), name, seen)) return true;
    for (Class<?> i : c.getInterfaces()) if (hasField(i, name, seen)) return true;
    return false;
  }
  public static void main(String[] a) throws Exception {
    List<URL> urls = new ArrayList<>();
    for (String p : a[1].split(":")) if (!p.isBlank()) urls.add(Path.of(p).toUri().toURL());
    URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    int missing = 0, unresolved = 0;
    for (String line : Files.readAllLines(Path.of(a[0]))) {
      String[] p = line.split("\t");
      String owner = p[1 - (p[0].equals("C") ? 1 : 0)].replace('/', '.');
      if (p[0].equals("C")) owner = p[1].replace('/', '.');
      Class<?> c;
      try { c = Class.forName(owner, false, cl); } catch (Throwable t) { System.out.println("MISSING CLASS " + owner + " (" + t.getClass().getSimpleName() + ")"); missing++; continue; }
      if (p[0].equals("C")) continue;
      try {
        boolean ok = p[0].equals("F") ? hasField(c, p[2], new HashSet<>()) : (hasMethod(c, p[2], p[3], new HashSet<>()) || c.isInterface() && hasMethod(Object.class, p[2], p[3], new HashSet<>()));
        if (!ok) { System.out.println("MISSING " + p[0] + " " + owner + "." + p[2] + " " + (p.length > 3 ? p[3] : "")); missing++; }
      } catch (RuntimeException e) { System.out.println("UNRESOLVED " + owner + "." + p[2] + " " + e.getMessage()); unresolved++; }
    }
    System.out.println("missing=" + missing + " unresolved=" + unresolved);
    if (missing + unresolved > 0) System.exit(1);
  }
}
