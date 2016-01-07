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

import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
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
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import javax.annotation.Nullable;
import java.io.File;
import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static japicmp.cli.JApiCli.ClassPathMode.TWO_SEPARATE_CLASSPATHS;

/**
 * Mojo executing the Binary Compatibility Enforcement.
 *
 * @author Andres Rodriguez
 */
@Mojo(name = "check")
public class JenkinsBCEMojo extends AbstractMojo {
    /**
     * Update center baseline specification.
     */
    private static final String UPDATE_CENTER = "update:";
    /**
     * Artifact version baseline specification.
     */
    private static final String VERSION = "version:";
    /**
     * Artifact baseline specification.
     */
    private static final String ARTIFACT = "artifact:";
    /**
     * Skip comparison baseline specification.
     */
    private static final String SKIP = "skip";
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
    /**
     * Baseline acquisition method. It can be:
     * <ul>
     * <li>{@code update:<i>url</i>}: use update center.</li>
     * <li>{@code version:<i>version</i>}: use the same artifact of the current project, with another version.</li>
     * <li>{@code artifact:<i>groupId</i>:<i>artifact</i>:<i>version</i>}: use the specified jar artifact.
     * </ul>
     */
    @Parameter(defaultValue = "update:https://updates.jenkins-ci.org/update-center.json")
    private String baseline;
    /**
     * Dependency inclusion specification. It can be:
     * <ul>
     * <li>{@code none}: don't include dependencies.</li>
     * <li>{@code all}: include all dependencies.</li>
     * <li>{@code include:<i>pcoord</i>,<i>pcoord</i>,...}: include only the specified dependencies.</li>
     * <li>{@code exclude:<i>pcoord</i>,<i>pcoord</i>,...}: include all but the specified dependencies.</li>
     * <li>{@code artifact:<i>groupId</i>:<i>artifact</i>:<i>version</i>}: use the specified jar artifact.
     * </ul>
     * In the last two cases, {@code <i>pcoord</i>} can be {@code artifact:<i>groupId</i>:<i>artifact</i>}
     * to match every version of the specified artifact or {@code artifact:<i>groupId</i>} to match every
     * artifact from the specified group.
     */
    @Parameter(defaultValue = "none")
    private String dependencySpec;

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

    private MojoFailureException failure(Throwable cause, String format, Object... args) {
        return new MojoFailureException(String.format(format, args), cause);
    }

    private MojoFailureException failure(String format, Object... args) {
        return new MojoFailureException(String.format(format, args));
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip()) {
            warn("Skipping execution.");
            return;
        }
        // Check we are in a plugin
        // We will check core later
        final String packaging = mavenProject.getPackaging();
        if (!"hpi".equals(packaging)) {
            warn("Not a Jenkins plugin. Skipping");
            return;
        }
        try {
            // Get the new package file.
            // final List<File> newVersion = ImmutableList.of(new File(projectBuildDir, mavenProject.getArtifactId() + ".jar"));
            final Iterable<File> newVersion = getNewVersionFiles();
            // Get the old package file
            final Iterable<File> oldVersion = getOldVersionFiles();
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
        } catch(MojoFailureException e) {
            error(e.getMessage());
            throw e;
        }

    }

    /**
     * @return Whether we should skip execution.
     */
    private boolean skip() {
        return mavenProject == null || baseline == null || baseline.startsWith(SKIP);
    }

    private Iterable<File> getNewVersionFiles() throws MojoFailureException {
        return new ResolvedArtifact(createArtifact(mavenProject.getGroupId(), mavenProject.getArtifactId(), mavenProject.getVersion())).getFiles();
    }

    private Iterable<File> getOldVersionFiles() throws MojoFailureException {
        ResolvedArtifact resolved = getUpdateCenterBaseline();
        if (resolved == null) {
            resolved = getVersionBaseline();
            if (resolved == null) {
                resolved = getArtifactBaseline();
                if (resolved == null) {
                    throw failure("Unable to resolve baseline");
                }
            }
        }
        return resolved.getFiles();
    }

    private String getBaselinePayload(String prefix) {
        if (baseline.startsWith(prefix)) {
            return baseline.substring(prefix.length());
        }
        return null;
    }

    /**
     * Creates a JAR artifact from Maven Coordinates.
     *
     * @return The created artifact. Must be resolved.
     */
    private Artifact createArtifact(String groupId, String artifactId, String version) {
        return new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion(version), Artifact.SCOPE_COMPILE, "jar", null, new DefaultArtifactHandler("jar"));
    }

    /**
     * Parses a JAR artifact from Maven Coordinates.
     *
     * @param coordinates Coordinates to parse.
     * @return The parsed artifact. Must be resolved.
     */
    private Artifact parseArtifact(String coordinates) throws MojoFailureException {
        final List<String> coords = Lists.newArrayList(Splitter.on(':').split(coordinates));
        if (coords.size() != 3) {
            throw failure("Invalid coordinates [%s]", coordinates);
        }
        return createArtifact(coords.get(0), coords.get(1), coords.get(2));
    }

    private ResolvedArtifact getUpdateCenterBaseline() throws MojoFailureException {
        final String url = getBaselinePayload(UPDATE_CENTER);
        if (url == null || url.isEmpty()) {
            return null;
        }
        final JsonElement updates;
        try {
            // TODO: look for internal Maven URL downloading methods (using proxies, etc.)
            final OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url(url).build();
            String response = client.newCall(request).execute().body().string();
            // TODO: review JSONP parsing
            response = response.substring(response.indexOf('{'));
            response = response.substring(0, response.lastIndexOf(')'));
            updates = new JsonParser().parse(new JsonReader(new StringReader(response)));
        } catch (Exception e) {
            throw failure(e, "Unable to get plugin information from update center [%s]", url);
        }
        // TODO: error reporting, new plugins, etc.
        final String coordinates;
        try {
            coordinates = updates.getAsJsonObject().get("plugins").getAsJsonObject().get(mavenProject.getArtifactId()).getAsJsonObject().get("gav").getAsString();
        } catch (RuntimeException e) {
            throw failure(e, "Unable to get plugin coordinates from update center [%s] info", url);
        }
        return new ResolvedArtifact(parseArtifact(coordinates));
    }

    private ResolvedArtifact getVersionBaseline() throws MojoFailureException {
        final String version = getBaselinePayload(VERSION);
        if (version == null || version.isEmpty()) {
            return null;
        }
        return new ResolvedArtifact(createArtifact(mavenProject.getGroupId(), mavenProject.getArtifactId(), version));
    }

    private ResolvedArtifact getArtifactBaseline() throws MojoFailureException {
        final String artifact = getBaselinePayload(ARTIFACT);
        if (artifact == null || artifact.isEmpty()) {
            return null;
        }
        return new ResolvedArtifact(parseArtifact(artifact));
    }

    private Options createOptions(Iterable<File> oldVersion, Iterable<File> newVersion) throws MojoFailureException {
        final boolean oldOk = checkFiles(oldVersion, "old");
        final boolean newOk = checkFiles(newVersion, "new");
        if (!oldOk || !newOk) {
            throw failure("There are unreadable files to compare");
        }
        final Options options = new Options();
        Iterables.addAll(options.getOldArchives(), oldVersion);
        Iterables.addAll(options.getNewArchives(), newVersion);
        options.setOutputOnlyModifications(true);
        options.setAccessModifier(AccessModifier.PROTECTED);
        options.setOutputOnlyBinaryIncompatibleModifications(true);
        options.setIncludeSynthetic(true);
        options.setIgnoreMissingClasses(true);
        options.setClassPathMode(TWO_SEPARATE_CLASSPATHS);
        infof("Comparing %s with baseline %s for binary compatibility enforcement", options.getNewArchives(), options.getOldArchives());
        return options;
    }

    private boolean checkFiles(Iterable<File> files, String collectionName) {
        final List<File> badFiles = Lists.newLinkedList(Iterables.filter(files, new Predicate<File>() {
            @Override
            public boolean apply(@Nullable File file) {
                return file == null || !file.exists() || !file.canRead();
            }
        }));
        if (!badFiles.isEmpty()) {
            errorf("Unreadable %s version files: %s", collectionName, badFiles);
            return false;
        }
        return true;
    }

    /**
     * Object representing a resolved artifact and (optionally) its transitively resolved artifacts.
     *
     * @author Andres Rodriguez
     */
    final class ResolvedArtifact {
        /**
         * Resolved Artifact.
         */
        private final Artifact artifact;
        /**
         * Resolved files.
         */
        private final List<File> files;

        /**
         * Constructor.
         */
        ResolvedArtifact(Artifact artifact) throws MojoFailureException {
            final DependencyPolicy dependencyPolicy = DependencyPolicy.of(dependencySpec);
            final ArtifactResolutionRequest request = new ArtifactResolutionRequest();
            request.setArtifact(artifact);
            request.setLocalRepository(localRepository);
            request.setRemoteRepositories(artifactRepositories);
            request.setResolutionFilter(dependencyPolicy);
            request.setResolveTransitively(dependencyPolicy.isTransitive());
            final ArtifactResolutionResult resolutionResult = artifactResolver.resolve(request);
            if (resolutionResult.hasExceptions()) {
                List<Exception> exceptions = resolutionResult.getExceptions();
                throw failure(exceptions.get(0), "Could not resolve artifact [%s]", artifact);
            }
            Set<Artifact> artifacts = resolutionResult.getArtifacts();
            if (artifacts.isEmpty()) {
                throw failure("Could not resolve artifact [%s]", artifact);
            }
            this.artifact = resolutionResult.getOriginatingArtifact();
            final ImmutableList.Builder<File> b = ImmutableList.builder();
            for (Artifact a : artifacts) {
                if (a.isResolved() && a.getFile() != null) {
                    b.add(a.getFile());
                }
            }
            this.files = b.build();
        }

        Artifact getArtifact() {
            return artifact;
        }

        List<File> getFiles() {
            return files;
        }
    }
}
