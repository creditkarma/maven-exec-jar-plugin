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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.Proxy;
import java.net.MalformedURLException;
import java.util.*;
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
public class JarOfJarsClassLoader extends com.creditkarma.plugin.AbstractClassLoader {


    // contains classes and native libraries
    private Map<String, byte[]> loadedClassBytes;
    // contains other things in the jars plus .class files so codeweaving works
    private Map<String, Map<String, byte[]>> loadedResources;
    // contains directories/packages in the jar
    private Set<String> loadedDirectories;



    public JarOfJarsClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    public JarOfJarsClassLoader(ClassLoader parent) {
        super(parent);
    }

    protected void init(File jarFileName) throws IOException {
        loadedClassBytes = new HashMap<>();
        loadedResources = new HashMap<>();
        loadedDirectories = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(jarFileName)) {
            loadInternalJar(jarFileName.getName(), fis, true);
        }

        Arrays.fill(internalBuf, (byte)0);
        logInfo("Found %d classes in %d jars\n",
                loadedClassBytes.size(), loadedResources.size());
    }

    protected byte[] findClassBytes(String resourceName) {
        return loadedClassBytes.get(resourceName);
    }


    private static boolean isClassOrNativeLibrary(String name) {
        return name.endsWith(".class") || name.endsWith(".so") ||
            name.endsWith(".dll") || name.endsWith(".dylib");
    }
    
    private void loadInternalJar(String name, InputStream stream, boolean first)
        throws IOException {

        Map<String, byte[]> jarResources = new HashMap<>();
        JarInputStream jarStream = new JarInputStream(stream);
        Manifest manifest = jarStream.getManifest();

        if (first) {
            initManifest(manifest);
        }

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
                    ByteArrayInputStream bais = new ByteArrayInputStream(out.toByteArray());
                    loadInternalJar(entryName, bais, false);
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

}
