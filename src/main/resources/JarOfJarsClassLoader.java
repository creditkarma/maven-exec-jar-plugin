/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.creditkarma.plugin;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.Proxy;
import java.net.MalformedURLException;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.SecureClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.jar.*;
import java.util.jar.Attributes.Name;

/**
 * The JarOfJarsClassLoader allows classes to be loaded both from a jar file
 * and from jar files enclosed in a jar file.  This class is designed to be
 * used in the exec-jar Maven plugin to allow a jar of jars containing
 * dependant jars to be placed into an executable jar and loaded by this
 * classloader.  
 * <p>
 * To use this classloader, create a launcher class to start your class
 * <code>com.mycompany.MyApp main()</code> method to start your application
 * <code>
<pre>
public class MyAppLauncher {

    public static void main(String[] args) {
        JarOfJarsClassLoader jcl = new JarOfJarsClassLoader();
        try {
            jcl.invokeMain("com.mycompany.MyApp", args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    } // main()

} // class MyAppLauncher
</pre>
* <p>
 * Use VM parameters in the command line for logging settings (examples):
 * <ul>
 * <li><code>-DJarOfJarsClassLoader.fileLogger=[filename]</code> for logging 
 * into a logfile.
 * The default is console.</li>
 * <li><code>-DJarOfJarsClassLoader.logger.level=FINE</code> for logging level.
 * The default level is INFO. See also {@link java.util.logging.Level}.</li>
 * </ul>
 * *
 * @author Hunter Payne
 * @since October 18, 2016
 */
public class JarOfJarsClassLoader extends SecureClassLoader {

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

    // contains classes and native libraries
    private Map<String, byte[]> loadedClassBytes;
    // contains other things in the jars plus .class files so codeweaving works
    private Map<String, Map<String, byte[]>> loadedResources;
    // contains directories/packages in the jar
    private Set<String> loadedDirectories;
    
    private ProtectionDomain topDomain;
    private final byte[] internalBuf = new byte[4096];

    public JarOfJarsClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    public JarOfJarsClassLoader(ClassLoader parent) {

        super(parent);

        // initialize logging here
        initLogging();
        logConfig("making JarOfJarsClassLoader with parent %s", parent);

        loadedClassBytes = new HashMap<String, byte[]>();
        loadedResources = new HashMap<String, Map<String, byte[]>>();
        loadedDirectories = new HashSet<String>();

        topDomain = getClass().getProtectionDomain();
        CodeSource jarSource = topDomain.getCodeSource();
        URL topJarUrl = jarSource.getLocation();
        assert("file".equals(topJarUrl.getProtocol()));

        try {
            String jarFileName =
                URLDecoder.decode(topJarUrl.getFile(), "UTF-8");
            File jarFile = new File(jarFileName);
            assert(jarFile.isFile());

            FileInputStream fis = new FileInputStream(jarFile);
            loadInternalJar(jarFileName, fis);
            fis.close();
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

        Arrays.fill(internalBuf, (byte)0);
        logInfo("Found %d classes in %d jars\n",
                loadedClassBytes.size(), loadedResources.size());
    }

    private static boolean isClassOrNativeLibrary(String name) {
        return name.endsWith(".class") || name.endsWith(".so") ||
            name.endsWith(".dll") || name.endsWith(".dylib");
    }
    
    private void loadInternalJar(String name, InputStream stream)
        throws IOException {

        Map<String, byte[]> jarResources = new HashMap<String, byte[]>();
        JarInputStream jarStream = new JarInputStream(stream);
        Manifest manifest = jarStream.getManifest();
        JarEntry entry;
        loadedResources.put(name, jarResources);

        while ((entry = jarStream.getNextJarEntry()) != null) {

            String entryName = entry.getName();
            if (!entry.isDirectory()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int len = 0;

                while (len != -1) {
                    len = jarStream.read(internalBuf);
                    if (len > 0) out.write(internalBuf, 0, len);
                }
                out.flush();
                out.close();

                if (!entryName.endsWith(".jar")) {
                    jarResources.put(entryName, out.toByteArray());
                    // read bytes into a byte[] and store them in
                    // loadedClassBytes
                    if (isClassOrNativeLibrary(entryName)) {
                        // rename classes so they can be found
                        if (entryName.endsWith(".class")) {
                            // change / to .
                            entryName =
                                entryName.replaceAll(File.separator, ".");
                            // remove .class
                            entryName =
                                entryName.substring(0, entryName.length() - 6);
                        }

                        if (!loadedClassBytes.containsKey(entryName)) {
                            loadedClassBytes.put(entryName, out.toByteArray());
                        } else {
                            // duplicate class in classpath!!!
                            // output warning here
                            byte[] loadedBytes =
                                loadedClassBytes.get(entryName);
                            byte[] dupBytes = out.toByteArray();
                            
                            if (Arrays.equals(dupBytes, loadedBytes)) {
                                logFiner(
                                    "Duplicate identical class %s found " +
                                    "multiple times including in %s",
                                    entryName, name);
                            } else {
                                logWarning(
                                    "Duplicate non-similar class %s found " + 
                                    "multiple times including in %s",
                                    entryName, name);
                            }
                        }
                    }
                } else {
                    ByteArrayInputStream bais =
                        new ByteArrayInputStream(out.toByteArray());
                    loadInternalJar(entryName, bais);
                }
            } else {

                // register we have seen this directory path
                loadedDirectories.add(entryName);
                if (entryName.endsWith(File.separator))
                    loadedDirectories.add(
                        entryName.substring(0, entryName.length() - 1));
                // define package
                String packageName = entryName.replaceAll(File.separator, ".");
                if (packageName.endsWith("."))
                    packageName =
                        packageName.substring(0, packageName.length() - 1);
                logFinest(
                    "defining package %s from entry %s",
                    packageName, entryName);
                definePackage(packageName, manifest);
            }
        }
        jarStream.close();
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

    /**
     * @see java.lang.ClassLoader#getResourceAsStream(java.lang.String)
     *
     * @return A stream containing the bytes of this resource
     */
    public InputStream getResourceAsStream(String name) {

        logFiner("finding resource as stream %s", name);

        // TODO load directory???
        for (String jarName : loadedResources.keySet()) {
            Map<String, byte[]> jarResources = loadedResources.get(jarName);
            byte[] b = jarResources.get(name);
            if (b != null) return new ByteArrayInputStream(b);
        }

        return super.getResourceAsStream(name);
    }

    /**
     * @see java.lang.ClassLoader#findResources(java.lang.String)
     *
     * @return An enumeration of {@link java.net.URL <tt>URL</tt>}
     * objects for the resources
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {

        Vector<URL> v = new Vector<URL>();
        logFiner("finding resources %s", name);
        
        for (String jarName : loadedResources.keySet()) {
            Map<String, byte[]> jarResources = loadedResources.get(jarName);
            byte[] b = jarResources.get(name);
            if (b != null) {
                // return a url that points at this byte array
                v.add(new URL(
                    "jar", null, -1, name, new JarOfJarsURLStreamHandler(b)));
                
            }
        }
        if (loadedDirectories.contains(name)) {
            // the default class loader will return a valid URL for 
            // directory names, but that URL will throw an exception
            // when openStream() is called on it.
            // add a useless entry for this directory
            v.add(new URL(
                "jar", null, -1, name, new JarOfJarsURLStreamHandler(null)));
        }

        Enumeration<URL> superVs = super.findResources(name);
        while (superVs.hasMoreElements()) v.add(superVs.nextElement());
        return v.elements();
    }

    /**
     * @see java.lang.ClassLoader#findResource(java.lang.String)
     *
     * @return A URL object for reading the resource, or null if the 
     * resource could not be found.
     * Example URL: jar:file:C:\...\some.jar!/resources/InnerText.txt
     */
    @Override
    protected URL findResource(String name) {

        logFiner("finding resource %s", name);
        
        for (String jarName : loadedResources.keySet()) {
            Map<String, byte[]> jarResources = loadedResources.get(jarName);
            byte[] b = jarResources.get(name);
            if (b != null) {
                try {
                    // return a url that points at this byte array
                    return new URL(
                        "jar", null, -1, name,
                        new JarOfJarsURLStreamHandler(b));
                } catch (MalformedURLException murle) {
                    murle.printStackTrace();
                }
            }
        }

        URL u = super.findResource(name);
        if (u != null) return u;
        if (loadedDirectories.contains(name)) {
            // the default class loader will return a valid URL for 
            // directory names, but that URL will throw an exception
            // when openStream() is called on it.
            // So return useless entry for this directory
            try {
                return new URL(
                    "jar", null, -1, name, new JarOfJarsURLStreamHandler(null));
            } catch (MalformedURLException murle) {
                murle.printStackTrace();
            }
        }

        return null;
    }

    /**
     * @see java.lang.ClassLoader#findLibrary(java.lang.String)
     *
     * @return The absolute path of the native library.
     */
    @Override
    protected String findLibrary(String lib) {
    
        logFine("finding lib %s", lib);
        
        byte[] b = loadedClassBytes.get(lib);
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
        
        byte[] b = loadedClassBytes.get(className);

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

    /**
     * The default <code>ClassLoader.defineClass()</code> does not create 
     * package for the loaded class and leaves it null. Each package 
     * referenced by this class loader must be created only once before the
     * <code>ClassLoader.defineClass()</code> call.
     * The base class <code>ClassLoader</code> keeps cache with created 
     * packages for reuse.
     *
     * @param className class to load
     * @param manifest manifest of the jar file that contains this package
     * @throws  IllegalArgumentException
     *          If package name duplicates an existing package either in 
     *          this class loader or one of its ancestors.
     */
    private void definePackage(String className, Manifest manifest)
    throws IllegalArgumentException {
        int pos = className.lastIndexOf('.');
        String packageName = "";
        if (pos > 0) packageName = className.substring(0, pos);
        if (null == getPackage(packageName)) {
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                String sealedUrlStr = attrs.getValue(Name.SEALED);
                URL sealedUrl = null;
                try {
                    if (sealedUrlStr != null) sealedUrl = new URL(sealedUrlStr);
                } catch (MalformedURLException murle) {
                    murle.printStackTrace();
                }
                definePackage(
                    packageName,
                    attrs.getValue(Name.SPECIFICATION_TITLE),
                    attrs.getValue(Name.SPECIFICATION_VERSION),
                    attrs.getValue(Name.SPECIFICATION_VENDOR),
                    attrs.getValue(Name.IMPLEMENTATION_TITLE),
                    attrs.getValue(Name.IMPLEMENTATION_VERSION),
                    attrs.getValue(Name.IMPLEMENTATION_VENDOR),
                    sealedUrl);
            } else {
                definePackage(
                    packageName, null, null, null, null, null, null, null);
            }
        }
    }

    private static class JarOfJarsConnection extends URLConnection {

        private byte[] b;

        public JarOfJarsConnection(URL url, byte[] bytes) {
            super(url);
            b = bytes;
        }

        public void connect() throws IOException {
        }

        public static boolean getDefaultAllowUserInteraction() {
            return false;
        }

        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(b);
        }
    }

    private static class JarOfJarsURLStreamHandler extends URLStreamHandler {

        private byte[] b;
        
        public JarOfJarsURLStreamHandler(byte[] bytes) {
            b = bytes;
        }

        protected URLConnection openConnection(URL u) throws IOException {
            return new JarOfJarsConnection(u, b);
        }

        protected URLConnection openConnection(URL u, Proxy p)
            throws IOException {
            return new JarOfJarsConnection(u, b);
        }
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
}
