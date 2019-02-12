package com.creditkarma.plugin;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class InlineJarClassLoader extends com.creditkarma.plugin.AbstractClassLoader {


    public InlineJarClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    public InlineJarClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected void init(File jarFileName) throws IOException {
        JarFile jarFile = new JarFile(jarFileName);
        Manifest manifest = jarFile.getManifest();
        initManifest(manifest);

    }

    @Override
    protected byte[] findClassBytes(String resourceName) {
        return new byte[0];
    }
}
