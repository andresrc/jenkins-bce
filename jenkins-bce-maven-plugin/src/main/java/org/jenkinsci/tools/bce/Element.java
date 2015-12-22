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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import japicmp.model.*;

import java.util.Set;

/**
 * Object collecting binary incompatible changes in an element.
 *
 * @author Andres Rodriguez
 */
final class Element<T extends JApiHasChangeStatus & JApiBinaryCompatibility & JApiHasAnnotations> {
    private static final String ANN_RESTRICTED = "org.kohsuke.accmod.Restricted";
    private static final String ACC_NOEXTERNALUSE = "org.kohsuke.accmod.restrictions.NoExternalUse";
    private static final String ANN_IGNORE = IgnoreBinaryIncompatibleChange.class.getName();
    private static final String ANN_ACCEPT = AcceptBinaryIncompatibleChange.class.getName();
    private static final String VALUE = "value";

    /**
     * Element.
     */
    private final T element;
    private final boolean noExternalUse;
    private final boolean accepted;
    private final boolean ignored;

    static <T extends JApiHasChangeStatus & JApiBinaryCompatibility & JApiHasAnnotations> Element<T> of(T element) {
        if (element == null) {
            return null;
        }
        return new Element<T>(element);
    }

    private static Iterable<JApiAnnotationElementValue> getValues(JApiAnnotation a) {
        for (JApiAnnotationElement e : a.getElements()) {
            if (VALUE.equals(e.getName())) {
                return e.getNewElementValues();
            }
        }
        return ImmutableList.of();
    }

    private static Set<String> getValues(JApiAnnotation a, JApiAnnotationElementValue.Type type) {
        final Set<String> values = Sets.newHashSet();
        for (JApiAnnotationElementValue v : getValues(a)) {
            if (v.getType() == type) {
                Object ev = v.getValue();
                if (ev != null) {
                    values.add(ev.toString());
                }
            }
        }
        return values;
    }

    private static Set<String> getStringValues(JApiAnnotation a) {
        return getValues(a, JApiAnnotationElementValue.Type.String);
    }

    private static Set<String> getClassValues(JApiAnnotation a) {
        return getValues(a, JApiAnnotationElementValue.Type.Class);
    }

    /**
     * Constructor.
     */
    private Element(T element) {
        this.element = element;
        boolean noExternalUse = false;
        boolean accepted = false;
        boolean ignored = false;
        for (JApiAnnotation a : element.getAnnotations()) {
            final String fqn = a.getFullyQualifiedName();
            if (ANN_RESTRICTED.equals(fqn)) {
                noExternalUse |= getClassValues(a).contains(ACC_NOEXTERNALUSE);
            } else if (ANN_ACCEPT.equals(fqn)) {
                accepted = true;
            } else if (ANN_IGNORE.equals(fqn)) {
                ignored = true;
            }
        }
        this.noExternalUse = noExternalUse;
        this.accepted = accepted;
        this.ignored = ignored;
    }

    public T getElement() {
        return element;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public boolean isIgnored() {
        return ignored;
    }

    public boolean isNoExternalUse() {
        return noExternalUse;
    }
}
