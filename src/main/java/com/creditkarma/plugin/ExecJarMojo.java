/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package com.creditkarma.plugin;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.regex.*;
import java.nio.file.Paths;
import java.nio.charset.Charset;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;

/**
 * Creates an executable jar version of the project's normal jar, 
 * including all dependencies.
 *
 * @goal exec-jar
 * @phase package
 * @requiresProject
 * @requiresDependencyResolution runtime
 */
public class ExecJarMojo extends AbstractMojo {

    /**
     * All the dependencies including trancient dependencies.
     *
     * @parameter default-value="${project.artifacts}"
     * @readonly
     * @required
     */
    private Collection<Artifact> artifacts;

    /**
     * All declared dependencies in this project, including system 
     * scoped dependencies.
     *
     * @parameter default-value="${project.dependencies}"
     * @readonly
     * @required
     */
    private Collection<Dependency> dependencies;

    /**
     * FileSet to be included in the "binlib" directory inside the exec-jar.
     * This is the place to include native libraries such as .dll files
     * and .so files. They will automatically be loaded by the exec-jar.
     * @parameter
     */
    private FileSet[] binlibs;

    /**
     * The directory for the resulting file.
     *
     * @parameter expression="${project.build.directory}"
     * @readonly
     */
    private File outputDirectory;

    /**
     * Name of the main JAR.
     *
     * @parameter expression="${project.build.finalName}"
     * @readonly
     */
    private String mainJarFilename;

    /**
     * Name of the generated JAR.
     *
     * @parameter alias="filename" 
     *   default-value="${project.build.finalName}"
     * @required
     */
    private String filename;

    /**
     * The version of exec-jar to use.  Has a default, so typically no 
     * need to specify this.
     *
     * @parameter property="execjar-version" default-value="0.97"
     */
    private String execjarVersion;

    /**
     * Whether to attach the generated exec-jar to the build. You may also 
     * wish to set <code>classifier</code>.
     *
     * @parameter default-value=false
     */
    private boolean attachToBuild;

    /**
     * Classifier to use, if the exec-jar is to be attached to the build.
     * Set <code>&lt;attachToBuild&gt;true&lt;/attachToBuild&gt; if you 
     * want that.
     *
     * @parameter property="classifier" default-value="executable"
     */
    private String classifier;

    /**
     * Launcher script template to use, defaults to script.sh.tpl
     *
     * @parameter property="scriptTemplate" default-value="script.sh.tpl"
     */
    private String scriptTemplate;

    /**
     * This Maven project.
     *
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;
    
    /**
     * For attaching artifacts etc.
     *
     * @component
     * @readonly
     */
    private MavenProjectHelper projectHelper;

    /**
     * The main class that exec-jar should activate
     *
     * @parameter alias="mainclass"
     */
    private String mainClass;

    /**
     * The version of the aspectjweaver library
     *
     * @parameter alias="aspectjweaverVersion" default-value="1.8.4"
     */
    private String aspectjweaverVersion;

    /**
     * The extra command line arguments to the launcher script
     *
     * @parameter alias="extraLauncherArgs" default-value=""
     */
    private String extraLauncherArgs = "";


	/**
	 * The dependency path, default is "lib"
	 *
	 * @parameter default-value="lib"
	 */
	private String libPath = "lib";


	/**
	 * The Native libraries, default is "binlib"
	 *
	 * @parameter alias="binlib" default-value="binlib"
	 */
	private String nativeLibPath = "binlib";

	/**
	 * The jar layout, default is "oneEntry"
	 *
	 * @parameter default-value="oneEntry"
	 */
	private String jarLayout = "oneEntry";

	/**
	 * Add class path in this manifest? if layout is inline, then addClasspath will be set to true
	 *
	 * @parameter default-value="false"
	 */
	private boolean addClasspath = false;


	/**
	 * Add the version information in the jar
	 */
	private String version = "1.0.7";




    public void execute() throws MojoExecutionException {

        // Show some info about the plugin.
        displayPluginInfo();

        JarOutputStream out = null;

        try {
            // Create the target file
            File execJarFile =
		new File(outputDirectory, filename + "-executable.jar");

	    Manifest manifest = getManifest();
	    String mainClass =
		manifest.getMainAttributes().getValue("Main-Class");
	    
	    if (this.mainClass != null) {
		mainClass = this.mainClass;
	    }

	    if ("".equals(mainClass) || null == mainClass) return;
	    int lastDotIdx = mainClass.lastIndexOf(".");
	    String mainClassName = mainClass.substring(lastDotIdx + 1);
	    String packageName = mainClass.substring(0, lastDotIdx);
	    String launcherClassName = null;

	    if (mainClassName.equalsIgnoreCase("main")) {
		launcherClassName = packageName + ".Main1";
	    } else {
		launcherClassName = packageName + ".Main";
	    }

	    // alter the manifest to point at the new launcher's name
	    manifest.getMainAttributes().putValue("Main-Class",
						  launcherClassName);

	    manifest.getMainAttributes().putValue("Exec-Jar-Version", version);
	    manifest.getMainAttributes().putValue("Exec-Jar-Layout", jarLayout);

	    JarLayout layout = JarLayout.JarLayouts.toLayout(jarLayout);
	    
            // Open a stream to write to the target file
            out = new JarOutputStream(
		new FileOutputStream(execJarFile, false), manifest);

            // Main jar
	    getLog().info("Adding main jar main/[" + mainJarFilename +
			  ".jar]");

	    // add launcher .class file
	    File launcherClass =
		compileLauncherMain(mainClass, launcherClassName);
	    addToZip(launcherClass, packageName.replace('.', '/') + "/",
		     out);

	    // add mainJarFilename directly to the executable jar (ie out)
	    File mainJarFile =
		new File(outputDirectory, mainJarFilename + ".jar");
	    JarInputStream mainIn =
		new JarInputStream(new FileInputStream(mainJarFile));
	    ZipEntry entry;
	    while ((entry = mainIn.getNextEntry()) != null) {
		if (!entry.getName().equalsIgnoreCase("manifest.mf")) {
		    addToZip(out, entry, mainIn);
		}
	    }
	    mainIn.close();
	    
            // All dependencies, including transient dependencies, but
	    // excluding system scope dependencies
            Map<File, Artifact> dependencyJars = extractDependencyFiles(artifacts);
            if (getLog().isDebugEnabled()) {
                getLog().debug("Adding [" + dependencyJars.size() +
			       "] dependency libraries...");
            }
            List<String> classPaths = new ArrayList<>();
            for (File jar : dependencyJars.keySet()) {
            	Artifact artifact = dependencyJars.get(jar);
				classPaths.add(layout.addJar(out, jar, libPath + "/", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getType()));
            }

            // System scope dependencies
            Map<File, Dependency> systemDependencyJars =
		extractSystemDependencyFiles(dependencies);
            if (getLog().isDebugEnabled()) {
                getLog().debug("Adding [" + systemDependencyJars.size() +
			       "] system dependency libraries...");
            }
            for (File jar : systemDependencyJars.keySet()) {
            	Dependency dependency = systemDependencyJars.get(jar);
				classPaths.add(layout.addJar(out, jar, libPath + "/", dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getType()));
            }

            if (layout == JarLayout.JarLayouts.Inline) {
				addClasspath = true;
			}

			if (addClasspath) {
				manifest.getMainAttributes().putValue("Class-Path", toString(classPaths, " "));
			}

            // Native libraries
            if (binlibs != null) {
                for (FileSet eachFileSet : binlibs) {
                    List<File> includedFiles = toFileList(eachFileSet);
                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Adding [" + includedFiles.size() +
				       "] native libraries...");
                    }
                    for (File eachIncludedFile : includedFiles) {
                        addToZip(eachIncludedFile, nativeLibPath + "/", out);
                    }
                }
            }

            // ExecJar stuff
            getLog().debug("Adding exec-jar component...");

	    // copy .class files into out
	    File classOutputDirectory =
		new File(this.outputDirectory, "classes");
	    File jarClClassFile =
		new File(classOutputDirectory,
			 "com/creditkarma/plugin/JarOfJarsClassLoader.class");
	    addToZip(jarClClassFile, "com/creditkarma/plugin/", out);
	    jarClClassFile =
		new File(classOutputDirectory,
			 "com/creditkarma/plugin/JarOfJarsClassLoader" +
			 "$JarOfJarsConnection.class");
	    addToZip(jarClClassFile, "com/creditkarma/plugin/", out);
	    jarClClassFile =
		new File(classOutputDirectory,
			 "com/creditkarma/plugin/JarOfJarsClassLoader" +
			 "$JarOfJarsURLStreamHandler.class");
	    addToZip(jarClClassFile, "com/creditkarma/plugin/", out);

            File execScriptFile =
		new File(outputDirectory, filename + "-executable.sh");
	    String scriptSrc = constructLauncherScript();
	    FileOutputStream fos = new FileOutputStream(execScriptFile);

	    fos.write(scriptSrc.getBytes("UTF8"));
	    fos.flush();
	    fos.close();
	    execScriptFile.setExecutable(true, true);
	    
	    // Attach the created execjar to the build.
	    if (attachToBuild) {
		projectHelper.attachArtifact(project, "jar", classifier,
					     execJarFile);
		projectHelper.attachArtifact(project, "sh", classifier,
					     execScriptFile);
	    }
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException("ExecJar Mojo failed.", e);
        } finally {
            if (out != null) {
		try {
		    out.close();
		} catch (final IOException ioe) {
		    // ignore
		}
	    }
        }
    }

    private void displayPluginInfo() {

        getLog().info("Using Exec-Jar to create a jar distribution");
        getLog().info("Using Exec-Jar version: " + execjarVersion);
	getLog().info("Using main class " + this.mainClass);
        getLog().info("Exec-Jar file: " +
		      outputDirectory.getAbsolutePath() + File.separator +
		      filename + "-executable.jar");
    }

    private Manifest getManifest() throws IOException {

	File mainJar = new File(outputDirectory, mainJarFilename + ".jar");
	JarInputStream in =
	    new JarInputStream(new FileInputStream(mainJar));
	Manifest mf = in.getManifest();
	if (mf != null) {
	    Manifest copy = new Manifest(mf);
	    in.close();
	    return copy;
	}
	in.close();
	return null;
    }

    // ----- Zip-file manipulations ---------------------------------------
    private void addToZip(File sourceFile, String zipfilePath,
			  JarOutputStream out) throws IOException {

        addToZip(out, new ZipEntry(zipfilePath + sourceFile.getName()),
		 new FileInputStream(sourceFile));
    }

    private final AtomicInteger alternativeEntryCounter =
	new AtomicInteger(0);

    static void addToZip(JarOutputStream out, ZipEntry entry,
			  InputStream in) throws IOException {
        try {
            out.putNextEntry(entry);
            copy(in, out);
            out.closeEntry();
        } catch(ZipException e) {
            if (!e.getMessage().startsWith("duplicate entry")){
                throw e;
            }
        }
    }

    public static long copyLarge(final InputStream input,
				 final OutputStream output, final byte[] buffer)
	throws IOException {
        long count = 0;
        int n = 0;
        while (-1 != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }
    
    public static int copy(final InputStream input, final OutputStream output)
	throws IOException {
	byte[] buf = new byte[4096];
        final long count = copyLarge(input, output, buf);
        if (count > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) count;
    }

    public static byte[] toByteArray(final InputStream input)
	throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    public static String toString(final InputStream input,
				  final Charset encoding) throws IOException {

	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	copy(input, baos);
	return new String(baos.toByteArray(), encoding);
    }
    
    private InputStream getFileBytes(ZipInputStream is, String name)
	throws IOException {
        ZipEntry entry = null;
        while ((entry = is.getNextEntry()) != null) {
            if (entry.getName().equals(name)) {
                byte[] data = toByteArray(is);
                return new ByteArrayInputStream(data);
            }
        }
        return null;
    }

    /**
     * Returns a {@link File} object for each artifact.
     *
     * @param artifacts Pre-resolved artifacts
     * @return <code>File</code> objects for each artifact.
     */
    private Map<File, Artifact> extractDependencyFiles(
	Collection<Artifact> artifacts) {
        Map<File, Artifact> files = new LinkedHashMap<>();

        if (artifacts == null){
            return files;
        }

        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();

            if (file.isFile()) {
                files.put(file, artifact);
            }
        }
        return files;
    }

    /**
     * Returns a {@link File} object for each system dependency.
     * @param systemDependencies a collection of dependencies
     * @return <code>File</code> objects for each system dependency in 
     * the supplied dependencies.
     */
    private Map<File, Dependency> extractSystemDependencyFiles(
	Collection<Dependency> systemDependencies) {
        final Map<File, Dependency> files = new LinkedHashMap<>();

        if (systemDependencies == null){
            return files;
        }

        for (Dependency systemDependency : systemDependencies) {
            if (systemDependency != null &&
		"system".equals(systemDependency.getScope())){
                files.put(new File(systemDependency.getSystemPath()), systemDependency);
            }
        }
        return files;
    }

    private String constructLauncherMain(
	String mainClass, String launcherPackage,
	String launcherMainClass, String classLoaderClass) {

	return "package " + launcherPackage +
	    ";\n\nimport com.creditkarma.plugin." + classLoaderClass + ";\n\n" +
	    "public class " + launcherMainClass +
	    " {\n\n  public static void main(String[] args) {\n" +
	    "    " + classLoaderClass + " jcl = new " + classLoaderClass + "();\n" +
	    "    try {\n      jcl.invokeMain(\"" +
	    mainClass + "\", args);\n    } catch (Throwable e) {\n" +
	    "      e.printStackTrace();\n    }\n  }\n}";
    }

    private String constructLauncherScript() throws IOException {

	ClassLoader cl = getClass().getClassLoader();
	InputStream scriptIn = cl.getResourceAsStream(scriptTemplate);
	if (null == scriptIn) {
	    getLog().info("Using custom script template: " + scriptTemplate);
	    scriptIn = new FileInputStream(scriptTemplate);
	}
	
	String scriptSrc = toString(scriptIn, Charset.forName("UTF8"));
	Pattern pattern = Pattern.compile("JWEAVER_VER");
	Matcher matcher = pattern.matcher(scriptSrc);
	scriptSrc = matcher.replaceAll(aspectjweaverVersion);

	pattern = Pattern.compile("EXTRA_ARGS");
	matcher = pattern.matcher(scriptSrc);
	scriptSrc = matcher.replaceAll(extraLauncherArgs);

	pattern = Pattern.compile("JAR_NAME");
	matcher = pattern.matcher(scriptSrc);
	return matcher.replaceAll(filename + "-executable.jar");
    }

    private void verifyIsDirectory(File dir) throws Exception {
	if (dir.exists()) {
	    if (!dir.isDirectory()) {
		throw new Exception(dir.getAbsolutePath() +
				    " isn't a directory");
	    }
	} else if (!dir.mkdirs()) {
	    throw new Exception("unable to create " +
				dir.getAbsolutePath());
	}
    }

    // returns the location of the .class file
    private File compileLauncherMain(
	String mainClass, String launcherClass) throws Exception {

	int lastDotIdx = launcherClass.lastIndexOf(".");
	String launcherClassName = launcherClass.substring(lastDotIdx + 1);
	String launcherPackage = launcherClass.substring(0, lastDotIdx);

	JarLayout layout = JarLayout.JarLayouts.toLayout(jarLayout);
	String classLoaderClass = layout == JarLayout.JarLayouts.Inline ? "InlineJarClassLoader" : "JarOfJarsClassLoader";
	String classLoaderClassFile = classLoaderClass + ".java";

	String launcherClassSource =
	    constructLauncherMain(mainClass, launcherPackage, launcherClassName, classLoaderClass);
	File outputDirectory =
	    new File(this.outputDirectory, "generated-sources");
	verifyIsDirectory(outputDirectory);

	String launcherPath = launcherPackage.replace('.', '/');
	File generatedPackageDir =
	    Paths.get(outputDirectory.getAbsolutePath(),
		      launcherPath).toFile();
	verifyIsDirectory(generatedPackageDir);

	File launcherSourceFile =
	    new File(generatedPackageDir, launcherClassName + ".java");
	FileOutputStream fos = new FileOutputStream(launcherSourceFile);
	byte[] sourceBytes = launcherClassSource.getBytes("UTF8");
	fos.write(sourceBytes);
	fos.flush();
	fos.close();


	ClassLoader cl = getClass().getClassLoader();
	InputStream srcIn = cl.getResourceAsStream(classLoaderClassFile);
	File srcOutputDirectory = new File(outputDirectory, "com/creditkarma/plugin/");
	verifyIsDirectory(srcOutputDirectory);
	
	File jarClSrcFile = new File(srcOutputDirectory, classLoaderClassFile);
	FileOutputStream srcOut = new FileOutputStream(jarClSrcFile);
	copy(srcIn, srcOut);
	srcOut.flush();
	srcOut.close();

	srcIn = cl.getResourceAsStream("AbstractClassLoader.java");
	srcOut = new FileOutputStream(new File(srcOutputDirectory, "AbstractClassLoader.java"));
	copy(srcIn, srcOut);
	srcOut.flush();
	srcOut.close();

	File classOutputDirectory =
	    new File(this.outputDirectory, "classes");
	List<String> compileClasspath =
	    project.getCompileClasspathElements();
	StringBuilder sb = new StringBuilder();

	for (String path : compileClasspath) {
	    if (0 == sb.length()) sb.append(File.pathSeparator);
	    sb.append(path);
	}

	com.sun.tools.javac.Main javac = new com.sun.tools.javac.Main();
	String[] options = new String[] {
	    "-cp", sb.toString(),
	    "-d", classOutputDirectory.getAbsolutePath(),
	    launcherSourceFile.getAbsolutePath(),
	    jarClSrcFile.getAbsolutePath()
	};
	if (0 == javac.compile(options)) {

	    File generatedClassesPackageDir =
		Paths.get(classOutputDirectory.getAbsolutePath(),
			  launcherPath).toFile();
	    return new File(generatedClassesPackageDir,
			    launcherClassName + ".class");
	}

	throw new Exception("compile failure");
    }

    private static List<File> toFileList(FileSet fileSet)
            throws IOException {
	
        File directory = new File(fileSet.getDirectory());
	if (directory.exists()) {
	    String includes = toString(fileSet.getIncludes());
	    String excludes = toString(fileSet.getExcludes());
	    return FileUtils.getFiles(directory, includes, excludes);
	}
	return Collections.<File>emptyList();
    }

	private static String toString(List<String> strings, String sep) {
		StringBuilder sb = new StringBuilder();
		for (String string : strings) {
			if (sb.length() > 0) {
				sb.append(sep);
			}
			sb.append(string);
		}
		return sb.toString();
	}

    private static String toString(List<String> strings) {
        return toString(strings, ", ");
    }
}
