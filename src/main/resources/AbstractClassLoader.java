package com.creditkarma.plugin;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Manifest;
import java.util.logging.Level;

public abstract class AbstractClassLoader extends SecureClassLoader {

    /** VM parameter key to turn on logging to file or console. */
    public static final String KEY_FILE_LOGGER =
            "JarOfJarsClassLoader.fileLogger";

    /**
     * VM parameter key to define log level.
     * Valid levels are defined in {@link java.util.logging.Level}.
     * Default value is {@link java.util.logging.Level#INFO}.
     */
    public static final String KEY_LOGGER_LEVEL =
            "JarOfJarsClassLoader.logger.level";

    private static Level logLevel = Level.INFO;
    private static PrintStream logger = null;

    private ProtectionDomain topDomain;


    protected String version;
    protected String jarLayout;
    protected List<String> classPaths;


    public AbstractClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    public AbstractClassLoader(ClassLoader parent) {
        super(parent);

        // initialize logging here
        initLogging();
        logConfig("making AbstractClassLoader with parent %s", parent);

        topDomain = getClass().getProtectionDomain();
        CodeSource jarSource = topDomain.getCodeSource();
        URL topJarUrl = jarSource.getLocation();
        assert("file".equals(topJarUrl.getProtocol()));

        try {
            String jarFileName = URLDecoder.decode(topJarUrl.getFile(), "UTF-8");
            File jarFile = new File(jarFileName);
            assert(jarFile.isFile());

            init(jarFile);
        } catch (UnsupportedEncodingException e) {
            System.err.printf(
                    "Failure to decode URL: %s %s\n", topJarUrl, e.toString());
            throw new RuntimeException("Can't load classloader");
        } catch (IOException ioe) {
            System.err.printf("problem decoding file %s\n", ioe.toString());
            throw new RuntimeException("Can't load classloader");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    protected abstract void init(File jarFile) throws IOException;

    protected abstract byte[] findClassBytes(String resourceName);

    protected void initManifest(Manifest manifest) {
        this.version = manifest.getMainAttributes().getValue("Exec-Jar-Version");
        this.jarLayout = manifest.getMainAttributes().getValue("Exec-Jar-Layout");
        this.classPaths = new ArrayList<>();
        String classPath = manifest.getMainAttributes().getValue("Class-Path");
        if (classPath != null) {
            this.classPaths.addAll(Arrays.asList(classPath.split(" ")));
        }
    }

    /**
     * @see java.lang.ClassLoader#findLibrary(java.lang.String)
     *
     * @return The absolute path of the native library.
     */
    @Override
    protected String findLibrary(String lib) {

        logFine("finding lib %s", lib);

        byte[] b = findClassBytes(lib);
        if (b != null) {
            assert(!lib.endsWith(".class"));
            try {
                int lastIdx = lib.lastIndexOf(".");
                assert(lastIdx != -1);

                String pre = lib.substring(0, lastIdx);
                String suf = lib.substring(lastIdx);
                // dynamically create a filename

                File f = File.createTempFile(pre, suf); //, dir);
                FileOutputStream fos = new FileOutputStream(f);

                // delete this file when the JVM exits
                f.deleteOnExit();

                // and then store these bytes in that file and finally
                fos.write(b);
                fos.flush();
                fos.close();

                // make read-only
                f.setReadable(true, true);
                f.setWritable(false);

                // make executable
                f.setExecutable(true, true);

                // return the name of this dynamically created file
                return f.getAbsolutePath();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }

        return super.findLibrary(lib);
    }

    @Override
    protected synchronized Class<?> loadClass(
            String className, boolean resolve) throws ClassNotFoundException {

        logFiner("loading class %s and resolve %b", className, resolve);

        byte[] b = findClassBytes(className);

        // Essential reading:
        // - Thread.getContextClassLoader() JavaDoc.
        // - http://www.javaworld.com/javaworld/javaqa/2003-06/01-qa-0606-load.html
        Thread.currentThread().setContextClassLoader(this);

        if (b != null) {
            Class<?> clazz = defineClass(className, b, 0, b.length, topDomain);
            if (resolve) resolveClass(clazz);
            return clazz;
        }

        Class<?> clazz = super.loadClass(className, resolve);
        if (resolve) resolveClass(clazz);
        return clazz;
    }


    protected void initLogging() {

        String logFile = System.getProperty(KEY_FILE_LOGGER);
        String logLevelStr = System.getProperty(KEY_LOGGER_LEVEL);
        if (logLevelStr != null) logLevel = Level.parse(logLevelStr);

        if (null == logFile) {
            logger = System.err;
        } else {
            try {
                logger = new PrintStream(logFile);
                System.out.println(
                        "classloader logging redirected to " + logFile);
            } catch (IOException ioe) {
                ioe.printStackTrace();
                logger = System.out;
            }
        }
    }

    protected void logFinest(String msg, Object ... obj) {
        log(Level.FINEST, msg, obj);
    }

    protected void logFiner(String msg, Object ... obj) {
        log(Level.FINER, msg, obj);
    }

    protected void logFine(String msg, Object ... obj) {
        log(Level.FINE, msg, obj);
    }

    protected void logConfig(String msg, Object ... obj) {
        log(Level.CONFIG, msg, obj);
    }

    protected void logInfo(String msg, Object ... obj) {
        log(Level.INFO, msg, obj);
    }

    protected void logWarning(String msg, Object ... obj) {
        log(Level.WARNING, msg, obj);
    }

    protected void logSevere(String msg, Object ... obj) {
        log(Level.SEVERE, msg, obj);
    }

    protected void log(Level level, String msg, Object ... obj) {
        if (level.intValue() >= logLevel.intValue()) {
            logger.printf(
                    "JarOfJarsClassLoader-" + level + ": " + msg + "\n", obj);
        }
    }


    public void invokeMain(String className, String[] args) throws Throwable {

        Class<?> clazz = loadClass(className);
        logInfo("Starting main method of: %s with ClassLoader=%s",
                className, clazz.getClassLoader());
        Method method =
                clazz.getMethod("main", new Class<?>[] { String[].class });

        if (method != null) {
            int modifiers = method.getModifiers();
            // make sure the main method is 'public static'
            if (!Modifier.isPublic(modifiers))
                throw new NoSuchMethodException("Main method isn't public");
            if (!Modifier.isStatic(modifiers))
                throw new NoSuchMethodException("Main method isn't static");

            Class<?> clazzRet = method.getReturnType();
            // make sure the return type is void
            if (clazzRet != void.class)
                throw new NoSuchMethodException(
                        "Main method has wrong return type " + clazzRet +
                                " should be void");
        }
        if (null == method) {
            throw new NoSuchMethodException(
                    "The main() method in class \"" + className +
                            "\" not found.");
        }

        // Invoke main method
        try {
            method.invoke(null, (Object)args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
