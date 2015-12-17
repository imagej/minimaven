/*
 * #%L
 * MiniMaven build system for small Java projects.
 * %%
 * Copyright (C) 2012 - 2015 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.minimaven;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.scijava.minimaven.JavaCompiler.CompileError;
import org.scijava.util.FileUtils;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * This class represents a parsed pom.xml file.
 * <p>
 * Every pom.xml file is parsed into an instance of this class; the tree of
 * projects shares a {@link BuildEnvironment} instance.
 * </p>
 *
 * @author Johannes Schindelin
 */
public class MavenProject implements Comparable<MavenProject> {

	protected final BuildEnvironment env;
	protected boolean buildFromSource, built;
	protected File directory, target;
	protected String sourceDirectory = "src/main/java";
	protected MavenProject parent;
	protected MavenProject[] children;

	protected Coordinate coordinate = new Coordinate(), parentCoordinate;
	protected Map<String, String> properties = new HashMap<String, String>();
	protected List<String> modules = new ArrayList<String>();
	protected List<Coordinate> dependencies = new ArrayList<Coordinate>();
	protected List<Coordinate> dependencyManagement = new ArrayList<Coordinate>();
	protected Set<String> repositories = new TreeSet<String>();
	protected String sourceVersion, targetVersion, mainClass;
	protected boolean includeImplementationBuild;
	protected String packaging = "jar";

	private static enum BooleanState {
			UNKNOWN, YES, NO
	}

	private BooleanState upToDate = BooleanState.UNKNOWN, jarUpToDate =
		BooleanState.UNKNOWN;

	private static Name CREATED_BY = new Name("Created-By");

	protected MavenProject addModule(final String name) throws IOException,
		ParserConfigurationException, SAXException
	{
		return addChild(env.parse(new File(new File(directory, name), "pom.xml"),
			this));
	}

	protected MavenProject addChild(final MavenProject child) {
		final MavenProject[] newChildren = new MavenProject[children.length + 1];
		System.arraycopy(children, 0, newChildren, 0, children.length);
		newChildren[children.length] = child;
		children = newChildren;
		return child;
	}

	protected MavenProject(final BuildEnvironment miniMaven, final File directory,
		final MavenProject parent)
	{
		env = miniMaven;
		this.directory = directory;
		this.parent = parent;
		if (parent != null) {
			coordinate.groupId = parent.coordinate.groupId;
			coordinate.version = parent.coordinate.version;
			parentCoordinate = parent.coordinate;
			includeImplementationBuild = parent.includeImplementationBuild;
		}
	}

	public void clean() throws IOException, ParserConfigurationException,
		SAXException
	{
		final String ijDirProperty = expand(getProperty(
			BuildEnvironment.IMAGEJ_APP_DIRECTORY));
		final File ijDir = ijDirProperty == null ? null : new File(ijDirProperty);
		clean(ijDir != null && ijDir.isDirectory() ? ijDir : null);
	}

	public void clean(final File ijDir) throws IOException,
		ParserConfigurationException, SAXException
	{
		if ("pom".equals(getPackaging())) {
			for (final MavenProject child : getChildren()) {
				if (child == null) continue;
				child.clean(ijDir);
			}
			return;
		}
		if (!buildFromSource) return;
		for (final MavenProject child : getDependencies(true,
			env.downloadAutomatically))
		{
			if (child != null) child.clean(ijDir);
		}
		if (target.isDirectory()) BuildEnvironment.rmRF(target);
		else if (target.exists()) target.delete();
		final File jar = getTarget();
		if (jar.exists()) jar.delete();
		final String fileName = jar.getName();
		if (fileName.endsWith(".jar") && ijDir != null) {
			deleteVersions(new File(ijDir, "plugins"), fileName, null);
			deleteVersions(new File(ijDir, "jars"), fileName, null);
		}
	}

	public void downloadDependencies() throws IOException,
		ParserConfigurationException, SAXException
	{
		getDependencies(true, true, "test");
		download();
	}

	protected void download() throws FileNotFoundException {
		if (buildFromSource || target.exists()) return;
		download(coordinate, true);
	}

	protected void download(final Coordinate dependency, final boolean quiet)
		throws FileNotFoundException
	{
		for (final String url : getRoot().getRepositories()) {
			try {
				if (env.debug) {
					env.err.println("Trying to download from " + url);
				}
				env.downloadAndVerify(url, dependency, quiet);
				return;
			}
			catch (final Exception e) {
				if (env.verbose) e.printStackTrace();
			}
		}
		throw new FileNotFoundException("Could not download " + dependency
			.getJarName());
	}

	public boolean upToDate(final boolean includingJar) throws IOException,
		ParserConfigurationException, SAXException
	{
		if (includingJar) {
			if (jarUpToDate == BooleanState.UNKNOWN) {
				jarUpToDate = checkUpToDate(true) ? BooleanState.YES : BooleanState.NO;
			}
			return jarUpToDate == BooleanState.YES;
		}
		if (upToDate == BooleanState.UNKNOWN) {
			upToDate = checkUpToDate(false) ? BooleanState.YES : BooleanState.NO;
		}
		return upToDate == BooleanState.YES;
	}

	public boolean checkUpToDate(final boolean includingJar) throws IOException,
		ParserConfigurationException, SAXException
	{
		if (!buildFromSource) return true;
		for (final MavenProject child : getDependencies(true,
			env.downloadAutomatically, "test"))
		{
			if (child != null && !child.upToDate(includingJar)) {
				if (env.verbose) {
					env.err.println(getArtifactId() + " not up-to-date because of " +
						child.getArtifactId());
				}
				return false;
			}
		}

		final File source = getSourceDirectory();

		final List<String> notUpToDates = new ArrayList<String>();
		long lastModified = addRecursively(notUpToDates, source, ".java", target,
			".class", false);
		int count = notUpToDates.size();

		// ugly work-around for Bio-Formats: EFHSSF.java only contains commented-out
		// code
		if (count == 1 && notUpToDates.get(0).endsWith(
			"poi/hssf/dev/EFHSSF.java"))
		{
			count = 0;
		}

		if (count > 0) {
			if (env.verbose) {
				final StringBuilder files = new StringBuilder();
				final int counter = 0;
				for (final String item : notUpToDates) {
					if (counter > 3) {
						files.append(", ...");
						break;
					}
					else if (counter > 0) {
						files.append(", ");
					}
					files.append(item);
				}
				env.err.println(getArtifactId() + " not up-to-date because " + count +
					" source files are not up-to-date (" + files + ")");
			}
			return false;
		}
		final long lastModified2 = updateRecursively(new File(source
			.getParentFile(), "resources"), target, true);
		if (lastModified < lastModified2) lastModified = lastModified2;
		if (includingJar) {
			final File jar = getTarget();
			if (!jar.exists() || jar.lastModified() < lastModified) {
				if (env.verbose) {
					env.err.println(getArtifactId() + " not up-to-date because " + jar +
						" is not up-to-date");
				}
				return false;
			}
		}
		return true;
	}

	public File getSourceDirectory() {
		final String sourcePath = getSourcePath();
		final File file = new File(sourcePath);
		if (file.isAbsolute()) return file;
		return new File(directory, sourcePath);
	}

	public String getSourcePath() {
		return expand(sourceDirectory);
	}

	protected void addToJarRecursively(final JarOutputStream out,
		final File directory, final String prefix) throws IOException
	{
		final File[] list = directory.listFiles();
		if (list == null) return;
		for (final File file : list) {
			if (file.isFile()) {
				// For backwards-compatibility with the Fiji Updater, let's not include
				// pom.properties files in the Updater itself
				if (file.getAbsolutePath().endsWith(
					"/Fiji_Updater/target/classes/META-INF/maven/sc.fiji/Fiji_Updater/pom.properties"))
					continue;
				out.putNextEntry(new ZipEntry(prefix + file.getName()));
				BuildEnvironment.copy(new FileInputStream(file), out, false);
			}
			else if (file.isDirectory()) {
				addToJarRecursively(out, file, prefix + file.getName() + "/");
			}
		}
	}

	/**
	 * Builds the artifact and installs it and its dependencies into
	 * ${imagej.app.directory}.
	 * <p>
	 * If the property <tt>imagej.app.directory</tt> does not point to a valid
	 * directory, the install step is skipped.
	 * </p>
	 *
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void buildAndInstall() throws CompileError, IOException,
		ParserConfigurationException, SAXException
	{
		final String ijDirProperty = expand(getProperty(
			BuildEnvironment.IMAGEJ_APP_DIRECTORY));
		if (ijDirProperty == null) {
			throw new IOException(BuildEnvironment.IMAGEJ_APP_DIRECTORY +
				" does not point to an ImageJ.app/ directory!");
		}
		buildAndInstall(new File(ijDirProperty), false);
	}

	/**
	 * Builds the project an its dependencies, and installs them into the given
	 * ImageJ.app/ directory structure.
	 * <p>
	 * If the property <tt>imagej.app.directory</tt> does not point to a valid
	 * directory, the install step is skipped.
	 * </p>
	 *
	 * @param ijDir the ImageJ.app/ directory
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void buildAndInstall(final File ijDir) throws CompileError,
		IOException, ParserConfigurationException, SAXException
	{
		buildAndInstall(ijDir, false);
	}

	/**
	 * Builds the project an its dependencies, and installs them into the given
	 * ImageJ.app/ directory structure.
	 * <p>
	 * If the property <tt>imagej.app.directory</tt> does not point to a valid
	 * directory, the install step is skipped.
	 * </p>
	 *
	 * @param ijDir the ImageJ.app/ directory
	 * @param forceBuild recompile even if the artifact is up-to-date
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void buildAndInstall(final File ijDir, final boolean forceBuild)
		throws CompileError, IOException, ParserConfigurationException,
		SAXException
	{
		if ("pom".equals(getPackaging())) {
			env.err.println("Looking at children of " + getArtifactId());
			for (final MavenProject child : getChildren()) {
				if (child == null) continue;
				child.buildAndInstall(ijDir, forceBuild);
			}
			final Set<MavenProject> dependencies = getDependencies(true, false,
				"test", "provided", "system");
			if (dependencies != null && dependencies.size() > 0) {
				for (final MavenProject project : getDependencies(true, false, "test",
					"provided", "system"))
				{
					project.copyToImageJAppDirectory(ijDir, true);
				}
			}
			return;
		}

		build(true, forceBuild);

		for (final MavenProject project : getDependencies(true, false, "test",
			"provided", "system"))
		{
			project.copyToImageJAppDirectory(ijDir, true);
		}
		copyToImageJAppDirectory(ijDir, true);
	}

	/**
	 * Builds the artifact.
	 *
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void buildJar() throws CompileError, IOException,
		ParserConfigurationException, SAXException
	{
		build(true, false);
	}

	/**
	 * Compiles the project.
	 *
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void build() throws CompileError, IOException,
		ParserConfigurationException, SAXException
	{
		build(false);
	}

	/**
	 * Compiles the project and optionally builds the .jar artifact.
	 *
	 * @param makeJar build a .jar file
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void build(final boolean makeJar) throws CompileError, IOException,
		ParserConfigurationException, SAXException
	{
		build(makeJar, false);
	}

	/**
	 * Compiles the project and optionally builds the .jar artifact.
	 *
	 * @param makeJar build a .jar file
	 * @param forceBuild for recompilation even if the artifact is up-to-date
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void build(final boolean makeJar, final boolean forceBuild)
		throws CompileError, IOException, ParserConfigurationException,
		SAXException
	{
		build(makeJar, forceBuild, false);
	}

	/**
	 * Compiles the project and optionally builds the .jar artifact.
	 *
	 * @param makeJar build a .jar file
	 * @param forceBuild for recompilation even if the artifact is up-to-date
	 * @param includeSources include sources if building a .jar file
	 * @throws CompileError
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void build(final boolean makeJar, final boolean forceBuild,
		final boolean includeSources) throws CompileError, IOException,
			ParserConfigurationException, SAXException
	{
		if (!forceBuild && upToDate(makeJar)) {
			return;
		}
		if (!buildFromSource || built) return;
		boolean forceFullBuild = false;
		for (final MavenProject child : getDependencies(true,
			env.downloadAutomatically, "test"))
		{
			if (child != null && !child.upToDate(makeJar)) {
				child.build(makeJar);
				forceFullBuild = true;
			}
		}

		// do not build aggregator projects
		final File source = getSourceDirectory();
		final File resources = new File(source.getParentFile(), "resources");
		if (!source.exists() && !resources.exists()) return;

		target.mkdirs();

		final List<String> arguments = new ArrayList<String>();
		// classpath
		final String classPath = getClassPath(true);
		MavenProject pom2 = this;
		while (pom2 != null && pom2.sourceVersion == null)
			pom2 = pom2.parent;
		if (pom2 != null) {
			arguments.add("-source");
			arguments.add(pom2.sourceVersion);
		}
		pom2 = this;
		while (pom2 != null && pom2.targetVersion == null)
			pom2 = pom2.parent;
		if (pom2 != null) {
			arguments.add("-target");
			arguments.add(pom2.targetVersion);
		}
		arguments.add("-classpath");
		arguments.add(classPath);
		// output directory
		arguments.add("-d");
		arguments.add(target.getPath());
		// the files
		int count = arguments.size();
		addRecursively(arguments, source, ".java", target, ".class",
			!forceFullBuild);
		count = arguments.size() - count;

		if (count > 0) {
			env.err.println("Compiling " + count + " file" + (count > 1 ? "s" : "") +
				" in " + directory);
			if (env.verbose) {
				env.err.println(arguments.toString());
				env.err.println("using the class path: " + classPath);
			}
			final String[] array = arguments.toArray(new String[arguments.size()]);
			if (env.javac != null) env.javac.call(array, env.verbose, env.debug);
		}

		updateRecursively(resources, target, false);

		final File pom = new File(directory, "pom.xml");
		if (pom.exists()) {
			final File targetFile = new File(target, "META-INF/maven/" +
				coordinate.groupId + "/" + coordinate.artifactId + "/pom.xml");
			targetFile.getParentFile().mkdirs();
			BuildEnvironment.copyFile(pom, targetFile);
		}

		final String manifestClassPath = getManifestClassPath();
		final File file = new File(target, "META-INF/MANIFEST.MF");
		Manifest manifest = null;
		if (file.exists()) {
			final InputStream in = new FileInputStream(file);
			manifest = new Manifest(in);
			in.close();
		}
		else {
			manifest = new Manifest();
			manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
			file.getParentFile().mkdirs();
		}
		final java.util.jar.Attributes main = manifest.getMainAttributes();
		if (mainClass != null) main.put(Name.MAIN_CLASS, mainClass);
		if (manifestClassPath != null) main.put(Name.CLASS_PATH, manifestClassPath);
		main.put(CREATED_BY, "MiniMaven");
		if (includeImplementationBuild && !getArtifactId().equals("Fiji_Updater"))
			main.put(new Name("Implementation-Build"), env.getImplementationBuild(
				directory));
		final OutputStream manifestOut = new FileOutputStream(file);
		manifest.write(manifestOut);
		manifestOut.close();

		if (makeJar) {
			final OutputStream jarOut = new FileOutputStream(getTarget());
			final JarOutputStream out = new JarOutputStream(jarOut);
			addToJarRecursively(out, target, "");
			if (includeSources) {
				if (pom.exists()) {
					out.putNextEntry(new ZipEntry("pom.xml"));
					BuildEnvironment.copy(new FileInputStream(pom), out, false);
				}
				addToJarRecursively(out, source, "src/main/java/");
				addToJarRecursively(out, resources, "src/main/resources/");
			}
			out.close();
			jarOut.close();
		}

		built = true;
	}

	protected long addRecursively(final List<String> list, final File directory,
		final String extension, final File targetDirectory,
		final String targetExtension, final boolean includeUpToDates)
	{
		long lastModified = 0;
		if (list == null) return lastModified;
		final File[] files = directory.listFiles();
		if (files == null) return lastModified;
		for (final File file : files) {
			if (file.isDirectory()) {
				final long lastModified2 = addRecursively(list, file, extension,
					new File(targetDirectory, file.getName()), targetExtension,
					includeUpToDates);
				if (lastModified < lastModified2) lastModified = lastModified2;
			}
			else {
				final String name = file.getName();
				if (!name.endsWith(extension) || name.equals("package-info.java")) {
					continue;
				}
				final File targetFile = new File(targetDirectory, name.substring(0, name
					.length() - extension.length()) + targetExtension);
				final long lastModified2 = file.lastModified();
				if (lastModified < lastModified2) lastModified = lastModified2;
				if (includeUpToDates || !targetFile.exists() || targetFile
					.lastModified() < lastModified2)
				{
					list.add(file.getPath());
				}
			}
		}
		return lastModified;
	}

	protected long updateRecursively(final File source, final File target,
		final boolean dryRun) throws IOException
	{
		long lastModified = 0;
		final File[] list = source.listFiles();
		if (list == null) return lastModified;
		for (final File file : list) {
			final File targetFile = new File(target, file.getName());
			if (file.isDirectory()) {
				final long lastModified2 = updateRecursively(file, targetFile, dryRun);
				if (lastModified < lastModified2) lastModified = lastModified2;
			}
			else if (file.isFile()) {
				final long lastModified2 = file.lastModified();
				if (lastModified < lastModified2) lastModified = lastModified2;
				if (dryRun || (targetFile.exists() && targetFile
					.lastModified() >= lastModified2))
				{
					continue;
				}
				targetFile.getParentFile().mkdirs();
				BuildEnvironment.copyFile(file, targetFile);
			}
		}
		return lastModified;
	}

	public Coordinate getCoordinate() {
		return coordinate;
	}

	public String getGAV() {
		return getGroupId() + ":" + getArtifactId() + ":" + getVersion() + ":" +
			getPackaging();
	}

	public String getGroupId() {
		return coordinate.groupId;
	}

	public String getArtifactId() {
		return coordinate.artifactId;
	}

	public String getVersion() {
		return coordinate.version;
	}

	public String getJarName() {
		return coordinate.getJarName();
	}

	public String getMainClass() {
		return mainClass;
	}

	public String getPackaging() {
		return packaging;
	}

	/**
	 * True iff artifact is a JAR file (i.e., {@link #getPackaging()} is "jar" or
	 * "bundle").
	 */
	public boolean isJAR() {
		return "jar".equals(getPackaging()) || "bundle".equals(getPackaging());
	}

	public File getTarget() {
		if (!buildFromSource) return target;
		return new File(new File(directory, "target"), getJarName());
	}

	public File getDirectory() {
		return directory;
	}

	public boolean getBuildFromSource() {
		return buildFromSource;
	}

	public String getClassPath(final boolean forCompile) throws IOException,
		ParserConfigurationException, SAXException
	{
		final StringBuilder builder = new StringBuilder();
		builder.append(target);
		if (env.debug) {
			env.err.println("Get classpath for " + coordinate + " for " + (forCompile
				? "compile" : "runtime"));
		}
		for (final MavenProject pom : getDependencies(true,
			env.downloadAutomatically, "test", forCompile ? "runtime" : "provided"))
		{
			if (env.debug) {
				env.err.println("Adding dependency " + pom.coordinate +
					" to classpath");
			}
			builder.append(File.pathSeparator).append(pom.getTarget());
		}
		return builder.toString();
	}

	private String getManifestClassPath() throws IOException,
		ParserConfigurationException, SAXException
	{
		final StringBuilder builder = new StringBuilder();
		for (final MavenProject pom : getDependencies(true,
			env.downloadAutomatically, "test", "provided"))
		{
			if (!pom.isJAR()) continue;
			builder.append(" ").append(pom.getArtifactId() + "-" +
				pom.coordinate.version + ".jar");
		}
		if (builder.length() == 0) return null;
		builder.delete(0, 1);
		return builder.toString();
	}

	private final void deleteVersions(final File directory, final String filename,
		final File excluding)
	{
		final File[] versioned = FileUtils.getAllVersions(directory, filename);
		if (versioned == null) return;
		for (final File file : versioned) {
			if (file.equals(excluding)) continue;
			if (!file.getName().equals(filename)) {
				env.err.println("Warning: deleting '" + file + "'");
			}
			if (!file.delete()) {
				env.err.println("Warning: could not delete '" + file + "'");
			}
		}
	}

	/**
	 * Copies the current artifact and all its dependencies into an ImageJ.app/
	 * directory structure.
	 * <p>
	 * In the ImageJ.app/ directory structure, plugin .jar files live in the
	 * plugins/ subdirectory while libraries not providing any plugins should go
	 * to jars/.
	 * </p>
	 *
	 * @param ijDir the ImageJ.app/ directory
	 * @throws IOException
	 */
	private void copyToImageJAppDirectory(final File ijDir,
		final boolean deleteOtherVersions) throws IOException
	{
		if ("pom".equals(getPackaging())) return;
		final File source = getTarget();
		if (!source.exists()) {
			if ("imglib-tests".equals(getArtifactId())) {
				// ignore obsolete ImgLib
				return;
			}
			if ("imglib2-tests".equals(getArtifactId())) {
				// ignore inherited kludge
				return;
			}
			throw new IOException("Artifact does not exist: " + source);
		}

		final File targetDir = new File(ijDir, getTargetDirectory(source));
		final File target = new File(targetDir, getArtifactId() + ("Fiji_Updater"
			.equals(getArtifactId()) ? "" : "-" + getVersion()) +
			(coordinate.classifier == null ? "" : "-" + coordinate.classifier) +
			".jar");
		if (!targetDir.exists()) {
			if (!targetDir.mkdirs()) {
				throw new IOException("Could not make directory " + targetDir);
			}
		}
		else if (target.exists() && target.lastModified() >= source
			.lastModified())
		{
			if (deleteOtherVersions) {
				deleteVersions(targetDir, target.getName(), target);
			}
			return;
		}
		if (deleteOtherVersions) deleteVersions(targetDir, target.getName(), null);
		BuildEnvironment.copyFile(source, target);
	}

	private static String getTargetDirectory(final File source) {
		if (isImageJ1Plugin(source)) return "plugins";
		final String name = source.getName();
		if (source.toURI().toString().contains("/ome/") || ((name.startsWith(
			"scifio-4.4.") || name.startsWith("jai_imageio-4.4.")) && source
				.getAbsolutePath().contains("loci")))
		{
			return "jars/bio-formats";
		}
		return "jars";
	}

	/**
	 * Determines whether a .jar file contains ImageJ 1.x plugins.
	 * <p>
	 * The test is simple: does it contain a <tt>plugins.config</tt> file?
	 * </p>
	 *
	 * @param file the .jar file
	 * @return whether it contains at least one ImageJ 1.x plugin.
	 */
	private static boolean isImageJ1Plugin(final File file) {
		final String name = file.getName();
		if (name.indexOf('_') < 0 || !file.exists()) return false;
		if (file.isDirectory()) {
			return new File(file, "src/main/resources/plugins.config").exists();
		}
		if (name.endsWith(".jar")) {
			try {
				final JarFile jar = new JarFile(file);
				for (final JarEntry entry : Collections.list(jar.entries()))
					if (entry.getName().equals("plugins.config")) {
						jar.close();
						return true;
					}
				jar.close();
			}
			catch (final Throwable t) {
				// obviously not a plugin...
			}
		}
		return false;
	}

	/**
	 * Copy the runtime dependencies
	 *
	 * @param directory where to copy the files to
	 * @param onlyNewer whether to copy the files only if the sources are newer
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public void copyDependencies(final File directory, final boolean onlyNewer)
		throws IOException, ParserConfigurationException, SAXException
	{
		for (final MavenProject pom : getDependencies(true,
			env.downloadAutomatically, "test", "provided"))
		{
			final File file = pom.getTarget();
			final File destination = new File(directory, pom.coordinate.artifactId +
				".jar");
			if (file.exists() && (!onlyNewer || (!destination.exists() || destination
				.lastModified() < file.lastModified())))
			{
				BuildEnvironment.copyFile(file, destination);
			}
		}
	}

	public Set<MavenProject> getDependencies() throws IOException,
		ParserConfigurationException, SAXException
	{
		return getDependencies(false, env.downloadAutomatically);
	}

	public Set<MavenProject> getDependencies(final boolean excludeOptionals,
		final boolean downloadAutomatically, final String... excludeScopes)
			throws IOException, ParserConfigurationException, SAXException
	{
		final Set<MavenProject> set = new TreeSet<MavenProject>();
		getDependencies(set, excludeOptionals, downloadAutomatically, null,
			excludeScopes);
		return set;
	}

	public void getDependencies(final Set<MavenProject> result,
		final boolean excludeOptionals, final boolean downloadAutomatically,
		Set<String> exclusions, final String... excludeScopes) throws IOException,
			ParserConfigurationException, SAXException
	{
		if (exclusions != null) exclusions = new LinkedHashSet<String>(exclusions);
		else exclusions = new LinkedHashSet<String>();
		for (final Coordinate dependency : dependencies) {
			if (excludeOptionals && dependency.optional) continue;
			final String scope = expand(dependency.scope);
			if (scope != null && excludeScopes != null && arrayContainsString(
				excludeScopes, scope))
			{
				continue;
			}
			final Coordinate expanded = expand(dependency);
			if (exclusions.size() > 0 && exclusions.contains(expanded.getGroupId() +
				":" + expanded.getArtifactId()))
			{
				continue;
			}
			addExclusions(exclusions, expanded);
			MavenProject pom = findPOM(expanded, !env.verbose, false);
			final String systemPath = expand(dependency.systemPath);
			if (pom == null && systemPath != null) {
				final File file = new File(systemPath);
				if (file.exists()) {
					result.add(env.fakePOM(file, expanded));
					continue;
				}
			}
			// make sure that snapshot .pom files are updated once a day
			if (!env.offlineMode && downloadAutomatically && pom != null &&
				pom.coordinate.version != null && (pom.coordinate.version.startsWith(
					"[") || pom.coordinate.version.endsWith("-SNAPSHOT")) && pom.directory
						.getPath().startsWith(BuildEnvironment.mavenRepository.getPath()))
			{
				if (maybeDownloadAutomatically(pom.coordinate, !env.verbose,
					downloadAutomatically))
				{
					if (pom.coordinate.version.startsWith("[")) {
						pom.coordinate.setSnapshotVersion(VersionPOMHandler.parse(new File(
							pom.directory.getParentFile(), "maven-metadata-version.xml")));
					}
					else {
						pom.coordinate.setSnapshotVersion(SnapshotPOMHandler.parse(new File(
							pom.directory, "maven-metadata-snapshot.xml")));
					}
					dependency.setSnapshotVersion(pom.coordinate.getVersion());
				}
			}
			if (pom == null && downloadAutomatically) {
				try {
					pom = findPOM(expanded, !env.verbose, downloadAutomatically);
				}
				catch (final IOException e) {
					env.err.println("Failed to download dependency " +
						expanded.artifactId + " of " + getArtifactId());
					throw e;
				}
			}
			if (pom == null || result.contains(pom)) continue;
			result.add(pom);
			try {
				pom.getDependencies(result, env.downloadAutomatically, excludeOptionals,
					exclusions, excludeScopes);
			}
			catch (final IOException e) {
				env.err.println("Problems downloading the dependencies of " +
					getArtifactId());
				throw e;
			}
		}
	}

	private void addExclusions(final Set<String> exclusions,
		final Coordinate dependency)
	{
		if (dependency.exclusions != null) exclusions.addAll(dependency.exclusions);
		final String groupId = dependency.getGroupId();
		final String artifactId = dependency.getArtifactId();
		queryDependencyManagement(new DependencyManagementCallback() {

			@Override
			public boolean coordinate(final MavenProject project,
				final Coordinate coordinate)
			{
				if (coordinate.exclusions != null && groupId.equals(project.expand(
					coordinate.groupId)) && artifactId.equals(project.expand(
						coordinate.artifactId)))
				{
					exclusions.addAll(coordinate.exclusions);
				}
				return false;
			}
		});
	}

	public List<Coordinate> getDirectDependencies() {
		final List<Coordinate> result = new ArrayList<Coordinate>();
		for (final Coordinate coordinate : dependencies) {
			result.add(expand(coordinate));
		}
		return result;
	}

	protected boolean arrayContainsString(final String[] array,
		final String key)
	{
		for (final String string : array) {
			if (string.equals(key)) return true;
		}
		return false;
	}

	// expands ${<property-name>}
	public Coordinate expand(final Coordinate dependency) {
		final boolean optional = dependency.optional;
		final String scope = expand(dependency.scope);
		final String groupId = expand(dependency.groupId);
		final String artifactId = expand(dependency.artifactId);
		String version = expand(dependency.version);
		final String classifier = expand(dependency.classifier);
		final String systemPath = expand(dependency.systemPath);
		final Set<String> exclusions = dependency.exclusions;
		if (version == null) {
			version = findVersion(groupId, artifactId);
		}
		return new Coordinate(groupId, artifactId, version, scope, optional,
			systemPath, classifier, exclusions);
	}

	private String findVersion(final String groupId, final String artifactId) {
		if (groupId == null || artifactId == null) {
			return null;
		}
		final String[] result = { null };
		queryDependencyManagement(new DependencyManagementCallback() {

			@Override
			public boolean coordinate(final MavenProject project,
				final Coordinate coordinate)
			{
				if (coordinate == null || coordinate.version == null || !groupId.equals(
					project.expand(coordinate.groupId)) || !artifactId.equals(project
						.expand(coordinate.artifactId)))
				{
					return false;
				}
				result[0] = project.expand(coordinate.version);
				return true;
			}
		});
		return result[0];
	}

	/**
	 * A callback for the
	 * {@link #queryDependencyManagement(DependencyManagementCallback)}.
	 *
	 * @author Johannes Schindelin
	 */
	private static interface DependencyManagementCallback {

		/**
		 * Handles one coordinate from the &lt;dependencyManagement&gt; section.
		 *
		 * @param project the project defining the coordinate
		 * @param coordinate the coordinate to handle
		 * @return whether to stop processing here
		 */
		boolean coordinate(final MavenProject project, final Coordinate coordinate);
	}

	private void queryDependencyManagement(
		final DependencyManagementCallback callback)
	{
		for (final Coordinate dependency : dependencyManagement) {
			if (callback.coordinate(this, dependency)) return;
		}
		for (MavenProject parent = this.parent; parent != null; parent =
			parent.parent)
		{
			for (final Coordinate dependency : parent.dependencies) {
				if (callback.coordinate(parent, dependency)) return;
			}
			for (final Coordinate dependency : parent.dependencyManagement) {
				if (callback.coordinate(parent, dependency)) return;
			}
		}
	}

	public String expand(final String string) {
		if (string == null) return null;
		String result = string;
		for (;;) {
			final int dollarCurly = result.indexOf("${");
			if (dollarCurly < 0) return result;
			final int endCurly = result.indexOf("}", dollarCurly + 2);
			if (endCurly < 0) throw new RuntimeException("Invalid string: " + string);
			String property = getProperty(result.substring(dollarCurly + 2,
				endCurly));
			if (property == null) {
				if (dollarCurly == 0 && endCurly == result.length() - 1) return null;
				property = "";
			}
			result = result.substring(0, dollarCurly) + property + result.substring(
				endCurly + 1);
		}
	}

	/**
	 * Returns the (possibly project-specific) value of a property.
	 * <p>
	 * System properties override project-specific properties to allow the user to
	 * overrule a setting by specifying it on the command-line.
	 * </p>
	 *
	 * @param key the name of the property
	 * @return the value of the property
	 */
	public String getProperty(final String key) {
		final String systemProperty = System.getProperty(key);
		if (systemProperty != null) return systemProperty;
		if (properties.containsKey(key)) return properties.get(key);
		if (key.equals("project.basedir")) return directory.getPath();
		if (key.equals("rootdir")) {
			File directory = this.directory;
			for (;;) {
				final File parent = directory.getParentFile();
				if (parent == null || !new File(parent, "pom.xml").exists()) {
					return directory.getPath();
				}
				directory = parent;
			}
		}
		if (parent == null) {
			// hard-code a few variables
			if (key.equals("bio-formats.groupId")) return "loci";
			if (key.equals("bio-formats.version")) return "4.4-SNAPSHOT";
			if (key.equals("imagej.groupId")) return "net.imagej";
			return null;
		}
		return parent.getProperty(key);
	}

	public MavenProject getParent() {
		return parent;
	}

	public MavenProject[] getChildren() {
		if (children == null) return new MavenProject[0];
		return children;
	}

	public MavenProject getRoot() {
		MavenProject result = this;
		while (result.parent != null) {
			result = result.parent;
		}
		return result;
	}

	protected Set<String> getRepositories() {
		final Set<String> result = new TreeSet<String>();
		getRepositories(result);
		return result;
	}

	protected void getRepositories(final Set<String> result) {
		// add a default to the root
		if (parent == null) {
			result.add(
				"http://maven.imagej.net/service/local/repo_groups/public/content/");
			result.add("http://repo1.maven.org/maven2/");
		}
		result.addAll(repositories);
		for (final MavenProject child : getChildren()) {
			if (child != null) child.getRepositories(result);
		}
	}

	public MavenProject findPOM(final Coordinate dependency, final boolean quiet,
		final boolean downloadAutomatically) throws IOException,
			ParserConfigurationException, SAXException
	{
		if (dependency.version == null && "aopalliance".equals(
			dependency.artifactId))
		{
			dependency.version = "1.0";
		}
		if (dependency.version == null && "provided".equals(dependency.scope)) {
			return null;
		}
		// work around MiniMaven's limitation to use only a single version of
		// pom-scijava
		if (dependency.groupId == null && dependency.artifactId != null) {
			if (dependency.artifactId.matches("scijava-common|minimaven")) {
				dependency.groupId = "org.scijava";
			}
			else if (dependency.artifactId.matches("imglib2.*")) {
				dependency.groupId = "net.imglib2";
			}
			else if (dependency.artifactId.matches("scifio")) {
				dependency.groupId = "io.scif";
			}
			else if (dependency.artifactId.matches("jama")) {
				dependency.groupId = "gov.nist.math";
			}
			else if (dependency.artifactId.matches("jpedalSTD")) {
				dependency.groupId = "org.jpedal";
			}
			else if (dependency.artifactId.equals("jep")) {
				dependency.groupId = "org.scijava";
			}
		}
		if (dependency.groupId == null) {
			throw new IllegalArgumentException("Need fully qualified GAVs: " +
				dependency.getGAV());
		}
		if (dependency.artifactId.equals(expand(coordinate.artifactId)) &&
			dependency.groupId.equals(expand(coordinate.groupId)) &&
			dependency.version.equals(expand(coordinate.version)))
		{
			return this;
		}
		final String key = dependency.getKey();
		if (env.localPOMCache.containsKey(key)) {
			final MavenProject result = env.localPOMCache.get(key); // may be null
			if (result == null || BuildEnvironment.compareVersion(dependency
				.getVersion(), result.coordinate.getVersion()) <= 0)
			{
				return result;
			}
		}

		// fall back to Fiji's modules/ and $HOME/.m2/repository/
		final MavenProject pom = findInMultiProjects(dependency);
		if (pom != null) return pom;

		if (env.ignoreMavenRepositories) {
			if (!quiet && !dependency.optional) {
				env.err.println("Skipping artifact " + dependency.artifactId +
					" (for " + coordinate.artifactId + "): not in jars/ nor plugins/");
			}
			return cacheAndReturn(key, null);
		}

		String path = BuildEnvironment.mavenRepository.getPath() + "/" +
			dependency.groupId.replace('.', '/') + "/" + dependency.artifactId + "/";
		if (dependency.version == null) {
			env.err.println("Skipping invalid dependency (version unset): " +
				dependency);
			return null;
		}
		if (dependency.version.startsWith("[") &&
			dependency.snapshotVersion == null)
		{
			try {
				if (!maybeDownloadAutomatically(dependency, quiet,
					downloadAutomatically))
				{
					return null;
				}
				if (dependency.version.startsWith("[")) {
					dependency.snapshotVersion = VersionPOMHandler.parse(new File(path,
						"maven-metadata-version.xml"));
				}
			}
			catch (final FileNotFoundException e) { /* ignore */ }
		}
		path += (dependency.version.endsWith("-SNAPSHOT") ? dependency.version
			: dependency.getVersion()) + "/";
		if (dependency.version.endsWith("-SNAPSHOT")) {
			try {
				if (!maybeDownloadAutomatically(dependency, quiet,
					downloadAutomatically))
				{
					return null;
				}
				if (dependency.version.endsWith("-SNAPSHOT")) {
					final File xml = new File(path, "maven-metadata-snapshot.xml");
					if (env.verbose) env.err.println("Parsing " + xml);
					if (xml.exists()) {
						try {
							dependency.setSnapshotVersion(SnapshotPOMHandler.parse(xml));
						}
						catch (final SAXException e) {
							env.err.println("[WARNING] problem parsing " + xml);
							e.printStackTrace(env.err);
						}
					}
					else {
						final File xml2 = new File(path,
							"maven-metadata-imagej.snapshots.xml");
						if (env.verbose) env.err.println("Parsing " + xml2);
						dependency.setSnapshotVersion(SnapshotPOMHandler.parse(xml2));
					}
				}
			}
			catch (final FileNotFoundException e) { /* ignore */ }
		}

		final File file = new File(path, dependency.getPOMName());
		if (!file.exists()) {
			if (downloadAutomatically) {
				if (!maybeDownloadAutomatically(dependency, quiet,
					downloadAutomatically))
				{
					return null;
				}
			}
			else {
				if (!quiet && !dependency.optional && !"system".equals(
					dependency.scope))
				{
					env.err.println("Skipping artifact " + dependency.getGAV() +
						" (for " + coordinate.getGAV() + "): not found");
				}
				if (!downloadAutomatically && env.downloadAutomatically) return null;
				return cacheAndReturn(key, null);
			}
		}

		final MavenProject result = env.parse(new File(path, dependency
			.getPOMName()), null, dependency.classifier);
		if (result != null) {
			if (result.target.getName().endsWith("-SNAPSHOT.jar")) {
				result.coordinate.version = dependency.version;
				result.target = new File(result.directory, dependency.getJarName());
			}
			if (result.parent == null) result.parent = getRoot();
			if (result.isJAR() && !new File(path, dependency.getJarName()).exists()) {
				if (downloadAutomatically) download(dependency, quiet);
				else {
					env.localPOMCache.remove(key);
					return null;
				}
			}
		}
		else if (!quiet && !dependency.optional) {
			env.err.println("Artifact " + dependency.artifactId + " not found" +
				(downloadAutomatically ? "" : "; consider 'get-dependencies'"));
		}
		return result;
	}

	protected MavenProject findInMultiProjects(final Coordinate dependency)
		throws IOException, ParserConfigurationException, SAXException
	{
		env.parseMultiProjects();
		final String key = dependency.getKey();
		final MavenProject result = env.localPOMCache.get(key);
		if (result != null && BuildEnvironment.compareVersion(dependency
			.getVersion(), result.coordinate.getVersion()) <= 0)
		{
			return result;
		}
		return null;
	}

	protected MavenProject cacheAndReturn(final String key,
		final MavenProject pom)
	{
		env.localPOMCache.put(key, pom);
		return pom;
	}

	protected boolean maybeDownloadAutomatically(final Coordinate dependency,
		final boolean quiet, final boolean downloadAutomatically)
	{
		if (!downloadAutomatically || env.offlineMode) return true;
		try {
			download(dependency, quiet);
		}
		catch (final Exception e) {
			if (!quiet && !dependency.optional) {
				e.printStackTrace(env.err);
				env.err.println("Could not download " + dependency.artifactId + ": " + e
					.getMessage());
			}
			final String key = dependency.getKey();
			env.localPOMCache.put(key, null);
			return false;
		}
		return true;
	}

	protected String findLocallyCachedVersion(final String path)
		throws IOException
	{
		final File file = new File(path, "maven-metadata-local.xml");
		if (!file.exists()) {
			final String[] list = new File(path).list();
			return list != null && list.length > 0 ? list[0] : null;
		}
		final BufferedReader reader = new BufferedReader(new FileReader(file));
		for (;;) {
			final String line = reader.readLine();
			if (line == null) {
				reader.close();
				throw new RuntimeException("Could not determine version for " + path);
			}
			final int tag = line.indexOf("<version>");
			if (tag < 0) continue;
			reader.close();
			final int endTag = line.indexOf("</version>");
			return line.substring(tag + "<version>".length(), endTag);
		}
	}

	protected void checkParentTag(final String tag, final String string1,
		final String string2)
	{
		if (!env.debug) return;
		final String expanded1 = expand(string1);
		final String expanded2 = expand(string2);
		if ((expanded1 == null && expanded2 != null) || (expanded1 != null &&
			!expanded1.equals(expanded2)))
		{
			env.err.println("Warning: " + tag + " mismatch in " + directory +
				"'s parent: " + string1 + " != " + string2);
		}
	}

	@Override
	public int compareTo(final MavenProject other) {
		int result = coordinate.artifactId.compareTo(other.coordinate.artifactId);
		if (result != 0) return result;
		if (coordinate.groupId != null && other.coordinate.groupId != null) {
			result = coordinate.groupId.compareTo(other.coordinate.groupId);
		}
		if (result != 0) return result;
		result = BuildEnvironment.compareVersion(coordinate.getVersion(),
			other.coordinate.getVersion());
		if (result != 0) return result;
		if (coordinate.classifier == null) {
			if (other.coordinate.classifier != null) return -1;
		}
		else if (other.coordinate.classifier == null) return +1;
		else result = coordinate.classifier.compareTo(other.coordinate.classifier);
		return result;
	}

	@Override
	public boolean equals(final Object other) {
		if (other instanceof MavenProject) {
			return compareTo((MavenProject) other) == 0;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return coordinate.getKey().hashCode();
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder();
		append(builder, "");
		return builder.toString();
	}

	public void append(final StringBuilder builder, final String indent) {
		builder.append(indent + coordinate.getKey() + "\n");
		if (children != null) {
			for (final MavenProject child : getChildren()) {
				if (child == null) builder.append(indent).append("  (null)\n");
				else child.append(builder, indent + "  ");
			}
		}
	}

	void parse(final InputStream in) throws IOException, SAXException,
		ParserConfigurationException
	{
		final XMLReader reader = SAXParserFactory.newInstance().newSAXParser()
			.getXMLReader();
		reader.setContentHandler(new XMLHandler());
		// reader.setXMLErrorHandler(...);
		reader.parse(new InputSource(in));
		in.close();
	}

	private class XMLHandler extends AbstractPOMHandler {

		// only used during parsing
		protected String prefix = "";
		protected Coordinate latestDependency = new Coordinate();
		protected boolean isCurrentProfile;
		protected String currentPluginName;
		private Coordinate latestExclusion = new Coordinate();

		// XML parsing

		@Override
		public void endDocument() {
			if (!properties.containsKey("project.groupId")) {
				properties.put(
				"project.groupId", coordinate.groupId);
			}
			if (!properties.containsKey("project.version")) {
				properties.put("project.version", coordinate.getVersion());
			}
		}

		@Override
		public void startElement(final String uri, final String name,
			final String qualifiedName, final Attributes attributes)
		{
			super.startElement(uri, name, qualifiedName, attributes);
			prefix += ">" + qualifiedName;
			if (env.debug) {
				env.err.println("start(" + uri + ", " + name + ", " + qualifiedName +
					", " + toString(attributes) + ")");
			}
		}

		@Override
		public void endElement(final String uri, final String name,
			final String qualifiedName) throws SAXException
		{
			super.endElement(uri, name, qualifiedName);
			if (prefix.equals(">project>dependencies>dependency") ||
				(isCurrentProfile && prefix.equals(
					">project>profiles>profile>dependencies>dependency")))
			{
				if (env.debug) {
					env.err.println("Adding dependendency " + latestDependency + " to " +
						this);
				}
				if (coordinate.artifactId.equals("javassist") &&
					latestDependency.artifactId.equals("tools"))
				{
					latestDependency.optional = false;
				}
				dependencies.add(latestDependency);
				latestDependency = new Coordinate();
			}
			else if (prefix.equals(
				">project>dependencyManagement>dependencies>dependency") ||
				(isCurrentProfile && prefix.equals(
					">project>profiles>profile>dependencyManagement>dependencies>dependency")))
			{
				if (env.debug) {
					env.err.println("Adding dependendency " + latestDependency + " to " +
						this);
				}
				dependencyManagement.add(latestDependency);
				latestDependency = new Coordinate();
			}
			else if (prefix.equals(
				">project>dependencies>dependency>exclusions>exclusion") ||
				(isCurrentProfile && prefix.equals(
					">project>profiles>profile>dependencies>dependency>exclusions>exclusion")))
			{
				if (latestDependency.exclusions == null) {
					latestDependency.exclusions = new HashSet<String>();
				}
				final String groupId = latestExclusion.getGroupId();
				final String artifactId = latestExclusion.getArtifactId();
				if (groupId != null && artifactId != null) {
					latestDependency.exclusions.add(groupId + ":" + artifactId);
				}
				latestExclusion = new Coordinate();
			}
			else if (prefix.equals(">project>profiles>profile")) {
				isCurrentProfile = false;
			}
			prefix = prefix.substring(0, prefix.length() - 1 - qualifiedName
				.length());
			if (env.debug) {
				env.err.println("end(" + uri + ", " + name + ", " + qualifiedName +
					")");
			}
		}

		@Override
		protected void processCharacters(final StringBuilder sb) {
			String string = sb.toString();
			if (env.debug) {
				env.err.println("characters: " + string + " (prefix: " + prefix + ")");
			}

			String prefix = this.prefix;
			if (isCurrentProfile) {
				prefix = ">project" + prefix.substring(">project>profiles>profile"
					.length());
			}

			if (prefix.equals(">project>groupId")) coordinate.groupId = string;
			else if (prefix.equals(">project>artifactId")) {
				coordinate.artifactId = string;
			}
			else if (prefix.equals(">project>version")) coordinate.version = string;
			else if (prefix.equals(">project>packaging")) packaging = string;
			else if (prefix.equals(">project>modules")) {
				// might not be building a target
				buildFromSource = true;
			}
			else if (prefix.equals(">project>modules>module")) modules.add(string);
			else if (prefix.startsWith(">project>properties>")) {
				properties.put(prefix.substring(">project>properties>".length()),
					string);
			}
			else if (prefix.equals(">project>dependencies>dependency>groupId") ||
				prefix.equals(
					">project>dependencyManagement>dependencies>dependency>groupId"))
			{
				latestDependency.groupId = string;
			}
			else if (prefix.equals(">project>dependencies>dependency>artifactId") ||
				prefix.equals(
					">project>dependencyManagement>dependencies>dependency>artifactId"))
			{
				latestDependency.artifactId = string;
			}
			else if (prefix.equals(">project>dependencies>dependency>version") ||
				prefix.equals(
					">project>dependencyManagement>dependencies>dependency>version"))
			{
				latestDependency.version = string;
			}
			else if (prefix.equals(">project>dependencies>dependency>scope") || prefix
				.equals(
					">project>dependencyManagement>dependencies>dependency>scope"))
			{
				latestDependency.scope = string;
			}
			else if (prefix.equals(">project>dependencies>dependency>optional") ||
				prefix.equals(
					">project>dependencyManagement>dependencies>dependency>optional"))
			{
				latestDependency.optional = string.equalsIgnoreCase("true");
			}
			else if (prefix.equals(">project>dependencies>dependency>systemPath") ||
				prefix.equals(
					">project>dependencyManagement>dependencies>dependency>systemPath"))
			{
				latestDependency.systemPath = string;
			}
			else if (prefix.equals(">project>dependencies>dependency>classifier") ||
				prefix.equals(
					">project>dependencyManagement>dependencies>dependency>classifier"))
			{
				latestDependency.classifier = string;
			}
			// for Bio-Formats' broken Maven dependencies, we need to support
			// exclusions
			else if (prefix.equals(
				">project>dependencies>dependency>exclusions>exclusion>groupId"))
			{
				latestExclusion.groupId = string;
			}
			else if (prefix.equals(
				">project>dependencies>dependency>exclusions>exclusion>artifactId"))
			{
				latestExclusion.artifactId = string;
			}
			else if (prefix.equals(">project>profiles>profile>id")) {
				isCurrentProfile = (!System.getProperty("os.name").equals("Mac OS X") &&
					"javac".equals(string)) || (coordinate.artifactId.equals(
						"javassist") && (string.equals("jdk16") || string.equals(
							"default-tools")));
				if (env.debug) {
					env.err.println((isCurrentProfile ? "Activating" : "Ignoring") +
						" profile " + string);
				}
			}
			else if (!isCurrentProfile && prefix.equals(
				">project>profiles>profile>activation>os>name"))
			{
				isCurrentProfile = string.equalsIgnoreCase(System.getProperty(
					"os.name"));
			}
			else if (!isCurrentProfile && prefix.equals(
				">project>profiles>profile>activation>os>family"))
			{
				final String osName = System.getProperty("os.name").toLowerCase();
				if (string.equalsIgnoreCase("windows")) {
					isCurrentProfile = osName.startsWith("win");
				}
				else if (string.toLowerCase().startsWith("mac")) {
					isCurrentProfile = osName.startsWith("mac");
				}
				else if (string.equalsIgnoreCase("unix")) {
					isCurrentProfile = !osName.startsWith("win") && !osName.startsWith(
						"mac");
				}
				else {
					env.err.println("Ignoring unknown OS family: " + string);
					isCurrentProfile = false;
				}
			}
			else if (!isCurrentProfile && prefix.equals(
				">project>profiles>profile>activation>file>exists"))
			{
				isCurrentProfile = new File(directory, string).exists();
			}
			else if (!isCurrentProfile && prefix.equals(
				">project>profiles>profile>activation>activeByDefault"))
			{
				isCurrentProfile = "true".equalsIgnoreCase(string);
			}
			else if (!isCurrentProfile && prefix.equals(
				">project>profiles>profile>activation>property>name"))
			{
				boolean negate = false;
				if (string.startsWith("!")) {
					negate = true;
					string = string.substring(1);
				}
				isCurrentProfile = negate ^ (expand("${" + string + "}") != null);
			}
			else if (prefix.equals(">project>repositories>repository>url")) {
				repositories.add(string);
			}
			else if (prefix.equals(">project>build>sourceDirectory")) {
				sourceDirectory = string;
			}
			else if (prefix.startsWith(">project>parent>")) {
				if (parentCoordinate == null) parentCoordinate = new Coordinate();
				if (prefix.equals(">project>parent>groupId")) {
					if (coordinate.groupId == null) coordinate.groupId = string;
					if (parentCoordinate.groupId == null) {
						parentCoordinate.groupId = string;
					}
					else checkParentTag("groupId", parentCoordinate.groupId, string);
				}
				else if (prefix.equals(">project>parent>artifactId")) {
					if (parentCoordinate.artifactId == null) {
						parentCoordinate.artifactId = string;
					}
					else {
						checkParentTag("artifactId", parentCoordinate.artifactId, string);
					}
				}
				else if (prefix.equals(">project>parent>version")) {
					if (coordinate.version == null) coordinate.version = string;
					if (parentCoordinate.version == null) {
						parentCoordinate.version = string;
					}
					else checkParentTag("version", parentCoordinate.version, string);
				}
			}
			else if (prefix.equals(">project>build>plugins>plugin>artifactId")) {
				currentPluginName = string;
				if (string.equals("buildnumber-maven-plugin")) {
					includeImplementationBuild = true;
				}
			}
			else if (prefix.equals(
				">project>build>plugins>plugin>configuration>source") &&
				"maven-compiler-plugin".equals(currentPluginName))
			{
				sourceVersion = string;
			}
			else if (prefix.equals(
				">project>build>plugins>plugin>configuration>target") &&
				"maven-compiler-plugin".equals(currentPluginName))
			{
				targetVersion = string;
			}
			else if (prefix.equals(
				">project>build>plugins>plugin>configuration>archive>manifest>mainClass") &&
				"maven-jar-plugin".equals(currentPluginName))
			{
				mainClass = string;
			}
			// This would be needed to compile clojure.jar. However,
			// it does not work because we do not support the antrun plugin.
//			else if (prefix.equals(
//				">project>build>plugins>plugin>executions>execution>configuration>sources>source") &&
//				"build-helper-maven-plugin".equals(currentPluginName))
//			{
//				sourceDirectory = string;
//			}
			else if (env.debug) env.err.println("Ignoring " + prefix);
		}

		private String toString(final Attributes attributes) {
			final StringBuilder builder = new StringBuilder();
			builder.append("[ ");
			for (int i = 0; i < attributes.getLength(); i++) {
				builder.append(attributes.getQName(i)).append("='").append(attributes
					.getValue(i)).append("' ");
			}
			builder.append("]");
			return builder.toString();
		}

	}
}
