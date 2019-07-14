package com.creditkarma.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * How to add a jar file into the executable jar
 *
 * @author Sheldon Shao
 * @version 1.0.7
 */
public interface JarLayout {

    /**
     * Add the given sourceJar into executable JarFile
     *
     *
     * @param mojo
     * @param execJar Executable Jar File
     * @param sourceJar SourceJar file
     * @param libPath Lib Path
     * @param groupId GroupId
     * @param artifactId ArtifactId
     * @param version Version
     * @param type Type of this artifact
     * @return Returns the class path information
     * @throws IOException If there is any exception
     */
    String addJar(ExecJarMojo mojo, JarOutputStream execJar, File sourceJar, String libPath,
                  String groupId, String artifactId, String version, String type) throws IOException;


    enum JarLayouts implements JarLayout {
        OneEntry {
            @Override
            public String addJar(ExecJarMojo mojo, JarOutputStream execJar, File sourceJar, String libPath,
                                 String groupId, String artifactId, String version, String type) throws IOException {
                String classPath = libPath + sourceJar.getName();
                try (InputStream inputStream = new FileInputStream(sourceJar)) {
                    JarEntry entry = new JarEntry(classPath);
                    mojo.addToZip(classPath, execJar, entry, inputStream);
                }
                return classPath;
            }
        },

        Inline {
            @Override
            public String addJar(ExecJarMojo mojo, JarOutputStream execJar, File sourceJar, String libPath,
                                 String groupId, String artifactId, String version, String type) throws IOException {

                String classPath = libPath + sourceJar.getName();
                String prefix = classPath + "/";

                try (JarFile jarFile = new JarFile(sourceJar)) {
                    Enumeration<JarEntry> enumeration = jarFile.entries();
                    while(enumeration.hasMoreElements()) {
                        JarEntry entry = enumeration.nextElement();
                        mojo.addToZip(classPath, execJar, new ZipEntry(prefix + entry.getName()), jarFile.getInputStream(entry));
                    }
                }
                return classPath;
            }
        };


        public static JarLayout toLayout(String name) {
            if ("inline".equalsIgnoreCase(name)) {
                return JarLayouts.Inline;
            }
            else {
                return JarLayouts.OneEntry;
            }
        }
    }
}
