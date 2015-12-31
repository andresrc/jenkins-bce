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
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.MojoFailureException;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Object representing the policy in including dependencies.
 *
 * @author Andres Rodriguez
 */
abstract class DependencyPolicy implements ArtifactFilter {
    /**
     * Default policy: no dependencies.
     */
    static final String POLICY_NONE = "none";
    /**
     * Policy: all dependencies.
     */
    static final String POLICY_ALL = "all";
    /**
     * Policy: include some dependencies.
     */
    static final String POLICY_INCLUDE = "include:";
    /**
     * Policy: exclude some dependencies.
     */
    static final String POLICY_EXCLUDE = "exclude:";

    /**
     * Policy singleton: none.
     */
    static final DependencyPolicy NONE = new None();
    /**
     * Policy singleton: all.
     */
    static final DependencyPolicy ALL = new All();

    /**
     * Splitter: fields.
     */
    private static final Splitter SPLIT_FIELDS = Splitter.on(',').omitEmptyStrings().trimResults();
    /**
     * Splitter: coordinates.
     */
    private static final Splitter SPLIT_COORDINATES = Splitter.on(':').omitEmptyStrings().trimResults();

    /**
     * Global exclusions.
     */
    private static Predicate<Artifact> EXCLUSIONS = Predicates.or(
            isArtifact("org.jenkins-ci.tools", "jenkins-bce-annotations"),
            isArtifact("com.google.code.findbugs", "jsr305"),
            isArtifact("com.google.code.findbugs", "annotations")
    );

    /**
     * Creates an artifact predicate based on groupId.
     */
    private static Predicate<Artifact> isGroup(final String groupId) {
        return new Predicate<Artifact>() {
            @Override
            public boolean apply(@Nullable Artifact artifact) {
                return artifact != null && groupId.equals(artifact.getGroupId());
            }
        };
    }

    /**
     * Creates an artifact predicate based on groupId and artifactId.
     */
    private static Predicate<Artifact> isArtifact(final String groupId, final String artifactId) {
        return new Predicate<Artifact>() {
            @Override
            public boolean apply(@Nullable Artifact artifact) {
                return artifact != null && groupId.equals(artifact.getGroupId()) && artifactId.equals(artifact.getArtifactId());
            }
        };
    }

    public static DependencyPolicy of(@Nullable String spec) throws MojoFailureException {
        if (spec == null) {
            return NONE;
        }
        spec = spec.trim();
        if (spec.isEmpty() || spec.startsWith(POLICY_NONE)) {
            return NONE;
        }
        if (spec.startsWith(POLICY_ALL)) {
            return ALL;
        }
        List<Predicate<Artifact>> predicates = parse(POLICY_INCLUDE, spec);
        if (predicates != null) {
            return new Include(predicates);
        }
        predicates = parse(POLICY_EXCLUDE, spec);
        if (predicates != null) {
            return new Exclude(predicates);
        }
        throw new MojoFailureException("Invalid dependency spec: " + spec);
    }

    private static List<Predicate<Artifact>> parse(String prefix, String spec) throws MojoFailureException {
        if (!spec.startsWith(prefix)) {
            return null;
        }
        final String arg = spec.substring(prefix.length()).trim();
        if (arg.isEmpty()) {
            throw new MojoFailureException("Invalid dependency spec: " + spec);
        }
        final List<Predicate<Artifact>> predicates = Lists.newLinkedList();
        for (String entry : SPLIT_FIELDS.split(arg)) {
            final List<String> coordinates = Lists.newArrayList(SPLIT_COORDINATES.split(entry));
            final int n = coordinates.size();
            if (n == 1) {
                predicates.add(isGroup(coordinates.get(0)));
            } else if (n == 2) {
                predicates.add(isArtifact(coordinates.get(0), coordinates.get(1)));
            } else {
                throw new MojoFailureException("Invalid entry [" + entry + "] when parsing dependency spec " + spec);
            }
        }
        if (predicates.isEmpty()) {
            throw new MojoFailureException("Invalid dependency spec: " + spec);
        }
        return predicates;
    }

    /**
     * Constructor.
     */
    private DependencyPolicy() {
    }

    /**
     * @return Whether resolution must be done transitively.
     */
    abstract boolean isTransitive();

    @Override
    public final boolean include(Artifact artifact) {
        return artifact != null && !artifact.isOptional() && !EXCLUSIONS.apply(artifact) && getPredicate().apply(artifact);
    }

    /**
     * @return The predicate to use to filter dependencies.
     */
    abstract Predicate<Artifact> getPredicate();

    /**
     * No dependencies included
     */
    private static class None extends DependencyPolicy {
        @Override
        boolean isTransitive() {
            return false;
        }

        @Override
        Predicate<Artifact> getPredicate() {
            return Predicates.alwaysFalse();
        }
    }

    /**
     * Some dependencies may be included
     */
    private static abstract class Some extends DependencyPolicy {
        @Override
        final boolean isTransitive() {
            return true;
        }
    }

    /**
     * All dependencies included
     */
    private static class All extends Some {
        @Override
        Predicate<Artifact> getPredicate() {
            return Predicates.alwaysTrue();
        }
    }

    /**
     * Include only those explicitly listed
     */
    private static class Include extends Some {
        private final Predicate<Artifact> predicate;

        Include(Iterable<Predicate<Artifact>> predicates) {
            this.predicate = Predicates.or(predicates);
        }

        @Override
        Predicate<Artifact> getPredicate() {
            return predicate;
        }
    }

    /**
     * Include all but those explicitly listed
     */
    private static class Exclude extends Some {
        private final Predicate<Artifact> predicate;

        Exclude(Iterable<Predicate<Artifact>> predicates) {
            this.predicate = Predicates.not(Predicates.or(predicates));
        }

        @Override
        Predicate<Artifact> getPredicate() {
            return predicate;
        }
    }

}
