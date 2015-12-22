/*
 * The MIT License
 *
 * Copyright (c) 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.tools.bce;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.AccessModifier;
import japicmp.model.JApiClass;
import japicmp.output.stdout.StdoutOutputGenerator;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.StringReader;
import java.util.List;

import static japicmp.cli.JApiCli.ClassPathMode.TWO_SEPARATE_CLASSPATHS;

/**
 * Mojo executing the Binary Compatibility Enforcement.
 *
 * @author Andres Rodriguez
 */
@Mojo(name = "check")
public class JenkinsBCEMojo extends AbstractMojo {
    /**
     * Default update center.
     */
    private static final String UPDATE_CENTER = "https://updates.jenkins-ci.org/update-center.json";

    /**
     * Current Maven Project.
     */
    @Parameter(defaultValue = "${project}")
    private MavenProject mavenProject;
    /**
     * Project build directory.
     */
    @Parameter(property = "project.build.directory", required = true)
    private File projectBuildDir;
    /**
     * Artifact resolver.
     */
    @Component
    private ArtifactResolver artifactResolver;
    @Parameter(defaultValue = "${localRepository}")
    /** Local Maven Repository. */
    private ArtifactRepository localRepository;
    /**
     * Project remote repositories.
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}")
    private List<ArtifactRepository> artifactRepositories;

    private void error(CharSequence s) {
        getLog().error(s);
    }

    private void errorf(String s, Object... args) {
        error(String.format(s, args));
    }

    private void warn(CharSequence s) {
        getLog().warn(s);
    }

    private void warnf(String s, Object... args) {
        warn(String.format(s, args));
    }

    private void info(CharSequence s) {
        getLog().info(s);
    }

    private void infof(String s, Object... args) {
        info(String.format(s, args));
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (mavenProject == null) {
            warn("No project configured. Skipping");
        }
        // Check we are in a plugin
        // We will check core later
        final String packaging = mavenProject.getPackaging();
        if (!"hpi".equals(packaging)) {
            warn("Not a Jenkins plugin. Skipping");
        }

        // Get the new package file.
        final File newVersion = new File(projectBuildDir, mavenProject.getArtifactId() + ".jar");
        if (!newVersion.isFile() && !newVersion.canRead()) {
            warnf("Unable to read generated file [%s]. Skipping.", newVersion);
        }
        // Get the old package file
        final File oldVersion = getOldVersionFile();
        if (!oldVersion.isFile() && !oldVersion.canRead()) {
            warnf("Unable to read generated file [%s]. Skipping.", oldVersion);
        }
        infof("Comparing [%s] with baseline [%s] for binary compatibility enforcement", newVersion.getAbsolutePath(), oldVersion.getAbsolutePath());
        final Options options = createOptions(oldVersion, newVersion);
        JarArchiveComparator jarArchiveComparator = new JarArchiveComparator(JarArchiveComparatorOptions.of(options));
        List<JApiClass> jApiClasses = jarArchiveComparator.compare(options.getOldArchives(), options.getNewArchives());
        // Filter the list
        final BinaryChanges changes = BinaryChanges.of(jApiClasses);
        if (!changes.isEmpty()) {
            // TODO: analyze if custom reporting needed
            StdoutOutputGenerator stdoutOutputGenerator = new StdoutOutputGenerator(options, Lists.newLinkedList(changes.getChangedClasses()));
            errorf("Binary Incompatible Changes Detected\n %s", stdoutOutputGenerator.generate());
            throw new MojoFailureException("Binary Incompatible Changes Detected");
        } else {
            if (changes.isIgnored()) {
                warn("You have ignored binary compatibility issues. Please think again");
            }
            if (changes.isAccepted()) {
                warn("You have accepted binary compatibility issues. Remember to document them in the release notes");
            }
        }
    }

    private File getOldVersionFile() throws MojoFailureException {
        // TODO: Make resolution method configurable.
        final JsonElement updates;
        try {
            // TODO: look for internal Maven URL downloading methods (using proxies, etc.)
            final OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url(UPDATE_CENTER).build();
            String response = client.newCall(request).execute().body().string();
            // TODO
            response = response.substring(response.indexOf('{'));
            response = response.substring(0, response.lastIndexOf(')'));
            updates = new JsonParser().parse(new JsonReader(new StringReader(response)));
        } catch (Exception e) {
            throw new MojoFailureException("Unable to get plugin information from update center", e);
        }
        final String groupId, artifactId, version;
        try {
            // TODO: error reporting, new plugins, etc.
            final String coordinates = updates.getAsJsonObject().get("plugins").getAsJsonObject().get(mavenProject.getArtifactId()).getAsJsonObject().get("gav").getAsString();
            infof("Comparing against [%s]", coordinates);
            List<String> coords = Lists.newArrayList(Splitter.on(':').split(coordinates));
            groupId = coords.get(0);
            artifactId = coords.get(1);
            version = coords.get(2);
        } catch (Exception e) {
            throw new MojoFailureException("Unable to get plugin coordinates from update center info", e);
        }
        final DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion(version), Artifact.SCOPE_COMPILE, "jar", null, new DefaultArtifactHandler("jar"));
        try {
            artifactResolver.resolve(artifact, artifactRepositories, localRepository);
            return artifact.getFile();
        } catch (ArtifactNotFoundException e) {
            throw new MojoFailureException("Unable to resolve old version", e);
        } catch (ArtifactResolutionException e) {
            throw new MojoFailureException("Unable to resolve old version", e);
        }
    }

    private Options createOptions(File oldVersion, File newVersion) {
        final Options options = new Options();
        options.getOldArchives().add(oldVersion);
        options.getNewArchives().add(newVersion);
        //options.setXmlOutputFile(Optional.fromNullable(pathToXmlOutputFile));
        //options.setHtmlOutputFile(Optional.fromNullable(pathToHtmlOutputFile));
        options.setOutputOnlyModifications(true);
        options.setAccessModifier(AccessModifier.PROTECTED);
        //options.addIncludeFromArgument(Optional.fromNullable(includes));
        //options.addExcludeFromArgument(Optional.fromNullable(excludes));
        options.setOutputOnlyBinaryIncompatibleModifications(true);
        options.setIncludeSynthetic(true);
        options.setIgnoreMissingClasses(true);
        options.setClassPathMode(TWO_SEPARATE_CLASSPATHS);
        //options.setHtmlStylesheet(Optional.fromNullable(pathToHtmlStylesheet));
        //options.setOldClassPath(Optional.fromNullable(oldClassPath));
        //options.setNewClassPath(Optional.fromNullable(newClassPath));
        return options;
    }

    private List<JApiClass> filter(List<JApiClass> classes) {
        final List<JApiClass> list = null;
        return null;
    }


}
