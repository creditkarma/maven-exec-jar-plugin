package com.creditkarma.plugin;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class InlineJarClassLoader extends com.creditkarma.plugin.AbstractClassLoader {

    private JarFile jarFile;

    /**
     * Index List
     *
     * package --> List[JarFile]
     */
    private Map<String, List<String>> packageToJars;

    public InlineJarClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    public InlineJarClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected void init(File jarFileName) throws IOException {
        jarFile = new JarFile(jarFileName);
        Manifest manifest = jarFile.getManifest();
        initManifest(manifest);
        loadIndexList(manifest);
    }

    private static final String CURRENT_JAR = "_EXEC_JAR_CURRENT_.jar";

    private void loadIndexList(Manifest manifest) throws IOException {
        ZipEntry zipEntry = jarFile.getEntry("META-INF/INDEX.LIST");
        packageToJars = new LinkedHashMap<>();
        if (zipEntry != null) {
            try {
                InputStream inputStream = jarFile.getInputStream(zipEntry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line = reader.readLine(); //Version info
                if ("JarIndex-Version: 1.0".equals(line)) {
                    reader.readLine();//Blank line

                    while(readSection(reader, manifest)) {
                    }
                }
            }
            catch (IOException ioe) {
                logWarning("Loading index list exception", ioe);
                throw ioe;
            }
        }
    }

    private boolean readSection(BufferedReader reader, Manifest manifest) throws IOException {
        String line = reader.readLine();
        if (line == null || line.isEmpty()) {
            return false;
        }
        String jar = line;
        if (CURRENT_JAR.equals(jar)) {
            jar = ""; //For current jar, use prefix ""
        }
        else {
            jar = jar + "/";
        }

        while((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            else {
                String packagName = line;
                if (line.startsWith("/")) {
                    packagName = line.substring(1);
                }

                List<String> list = packageToJars.get(packagName);
                if (list == null) {
                    list = new ArrayList<>(2);
                    packageToJars.put(packagName, list);
                }

                if (!packagName.isEmpty()) {
                    definePackage(packagName.replace('/', '.'), manifest);
                }
                list.add(jar);
            }
        }
        return true;
    }

    @Override
    protected byte[] findClassBytes(String resourceName) {
        if (resourceName == null) {
            throw new IllegalArgumentException("Resource name is null");
        }
        resourceName = resourceName.replace('.', '/') + ".class";
        int lastIndex = resourceName.lastIndexOf('/');
        String packageName = lastIndex > 0 ? resourceName.substring(0, lastIndex+1) : "";
        List<String> jars = packageToJars.get(packageName);
        if (jars != null && !jars.isEmpty()) {
            for(String jar: jars) {
                String entryName = jar + resourceName;
                ZipEntry entry = jarFile.getEntry(entryName);
                if (entry != null) {
                    try {
                        return entryToBytes(entry);
                    }
                    catch(IOException ioe) {
                        logWarning("Loading entry error:" + entryName, ioe);
                    }
                }
            }
        }
        return null;
    }

    protected byte[] entryToBytes(ZipEntry entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = 0;

        InputStream inputStream = jarFile.getInputStream(entry);
        while (len != -1) {
            len = inputStream.read(internalBuf);
            if (len > 0) out.write(internalBuf, 0, len);
        }
        out.flush();
        byte[] bytes = out.toByteArray();
        inputStream.close();
        out.close();
        return bytes;
    }


    /**
     * @see java.lang.ClassLoader#getResourceAsStream(java.lang.String)
     *
     * @return A stream containing the bytes of this resource
     */
    public InputStream getResourceAsStream(String resourceName) {

        logFiner("finding resource as stream %s", resourceName);

        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }

        int lastIndex = resourceName.lastIndexOf('/');
        String packageName = lastIndex > 0 ? resourceName.substring(0, lastIndex+1) : "";
        List<String> jars = packageToJars.get(packageName);

        if (jars != null && !jars.isEmpty()) {
            for(String jar: jars) {
                String entryName = jar + resourceName;
                ZipEntry entry = jarFile.getEntry(entryName);
                if (entry != null) {
                    try {
                        return new ByteArrayInputStream(entryToBytes(entry));
                    }
                    catch(IOException ioe) {
                        logWarning("Loading entry exception:" + entryName, ioe);
                    }
                }
            }
        }
        return super.getResourceAsStream(resourceName);
    }

    /**
     * @see java.lang.ClassLoader#findResources(java.lang.String)
     *
     * @return An enumeration of {@link java.net.URL <tt>URL</tt>}
     * objects for the resources
     */
    @Override
    public Enumeration<URL> findResources(String resourceName) throws IOException {
        Vector<URL> v = new Vector<>();
        logFiner("finding resources %s", resourceName);

        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }


        int lastIndex = resourceName.lastIndexOf('/');
        String packageName = lastIndex > 0 ? resourceName.substring(0, lastIndex+1) : "";
        List<String> jars = packageToJars.get(packageName);

        if (jars != null && !jars.isEmpty()) {
            for(String jar: jars) {
                String entryName = jar + resourceName;
                ZipEntry entry = jarFile.getEntry(entryName);
                if (entry != null) {
                    v.add(new URL("jar:" + topJarUrl.toExternalForm() + "!/" + entryName));
                }
            }
        }
        Enumeration<URL> superVs = super.findResources(resourceName);
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
    protected URL findResource(String resourceName) {
        logFiner("finding resource %s", resourceName);

        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }

        int lastIndex = resourceName.lastIndexOf('/');
        String packageName = lastIndex > 0 ? resourceName.substring(0, lastIndex+1) : "";
        List<String> jars = packageToJars.get(packageName);

        if (jars != null && !jars.isEmpty()) {
            for(String jar: jars) {
                String entryName = jar + resourceName;
                ZipEntry entry = jarFile.getEntry(entryName);
                if (entry != null) {
                    try {
                        return new URL("jar:" + topJarUrl.toExternalForm() + "!/" + entryName);
                    } catch (MalformedURLException murle) {
                        murle.printStackTrace();
                    }
                }
            }
        }

        URL u = super.findResource(resourceName);
        if (u != null) return u;
        return null;
    }

}
