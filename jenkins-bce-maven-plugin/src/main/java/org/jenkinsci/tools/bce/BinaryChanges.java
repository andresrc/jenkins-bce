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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.*;
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

import javax.annotation.Nullable;
import java.io.File;
import java.util.Iterator;
import java.util.List;

import static japicmp.cli.JApiCli.ClassPathMode.TWO_SEPARATE_CLASSPATHS;

/**
 * Object representing the detected binary incompatible changes.
 *
 * @author Andres Rodriguez
 */
final class BinaryChanges {
    /**
     * Changed classes.
     */
    private final ImmutableList<JApiClass> changedClasses;
    /**
     * Whether there are accepted changes.
     */
    private final boolean accepted;
    /**
     * Whether there are ignored changes.
     */
    private final boolean ignored;

    /**
     * Factory method.
     */
    static BinaryChanges of(Iterable<JApiClass> classes) {
        final Builder b = new Builder();
        if (classes != null) {
            for (JApiClass c : classes) {
                b.add(c);
            }
        }
        return new BinaryChanges(b);
    }

    /**
     * Constructor.
     */
    private BinaryChanges(Builder builder) {
        this.changedClasses = builder.changedClasses.build();
        this.accepted = builder.accepted;
        this.ignored = builder.ignored;
    }

    public ImmutableList<JApiClass> getChangedClasses() {
        return changedClasses;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public boolean isIgnored() {
        return ignored;
    }

    public boolean isEmpty() {
        return changedClasses.isEmpty();
    }

    private static final class Builder {
        private final ImmutableList.Builder<JApiClass> changedClasses = ImmutableList.builder();
        private boolean accepted = false;
        private boolean ignored = false;

        Builder() {
        }

        /**
         * Adds a class to the list. Checks if the class must be filtered out.
         */
        void add(JApiClass klass) {
            // In this version we only keep binary incompatible changes
            // TODO In the future we must dive into binary compatible changes to look for unneeded annotations.
            final Element<JApiClass> classElement = element(klass);
            if (classElement != null) {
                // TODO: some incompatibilities are being filtered here.
                final boolean interfaces = filter(klass.getInterfaces());
                final boolean fields = filterElements(klass.getFields());
                final boolean constructors = filterElements(klass.getConstructors());
                final boolean methods = filterElements(klass.getMethods());
                final boolean annotations = filter(klass.getAnnotations());
                if (interfaces || fields || constructors || methods || annotations) {
                    changedClasses.add(klass);
                }
            }
        }

        private <T extends JApiHasChangeStatus & JApiBinaryCompatibility & JApiHasAnnotations> Element<T> element(T element) {
            // TODO: check for unneeded annotations.
            if (element != null && !element.isBinaryCompatible()) {
                final Element<T> e = Element.of(element);
                // TODO: adding NoExternalUse to a formerly public element should be marked as incompatible.
                if (e != null && !e.isNoExternalUse()) {
                    accepted |= e.isAccepted();
                    ignored |= e.isIgnored();
                    if (!e.isAccepted() && !e.isIgnored()) {
                        return e;
                    }
                }
            }
            return null;
        }

        private <T extends JApiBinaryCompatibility> boolean filter(List<T> list) {
            for (Iterator<T> i = list.iterator(); i.hasNext(); ) {
                final T t = i.next();
                if (t == null || t.isBinaryCompatible()) {
                    i.remove();
                }
            }
            return !list.isEmpty();
        }

        private <T extends JApiHasChangeStatus & JApiBinaryCompatibility & JApiHasAnnotations> boolean filterElements(List<T> list) {
            for (Iterator<T> i = list.iterator(); i.hasNext(); ) {
                final T t = i.next();
                final Element<T> e = element(t);
                if (e == null) {
                    i.remove();
                }
            }
            return !list.isEmpty();
        }
    }
}
