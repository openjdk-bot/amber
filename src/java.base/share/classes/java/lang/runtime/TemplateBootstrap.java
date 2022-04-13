/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.runtime;


import java.lang.invoke.*;
import java.lang.reflect.Modifier;
import java.lang.runtime.Carriers.*;
import java.lang.TemplatePolicy.PolicyLinkage;
import java.util.*;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.vm.annotation.Stable;

/**
 * This class constructs a {@link CallSite} to handle templated
 * string processing.
 *
 * @since 19
 */
@PreviewFeature(feature=PreviewFeature.Feature.TEMPLATED_STRINGS)
public final class TemplateBootstrap {

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    /**
     * {@link MethodHandle} to {@link TemplatedStringCarrier}
     * constructor.
     */
    private static final MethodHandle TEMPLATED_STRING_CARRIER_MH;

    /**
     * {@link MethodHandle} to {@link newTemplatedString}
     * method.
     */
    private static final MethodHandle NEW_TEMPLATED_STRING;

    /**
     * {@link MethodHandle} to {@link List#of(Object[])}
     */
    private static final MethodHandle LIST_OF_MH;

    /**
     * {@link MethodHandle} to {@link TemplatePolicy#apply}
     */
    private static final MethodHandle APPLY_MH;

    /**
     * {@link MethodHandle} to {@link TemplateBootstrap#firstInvoke}
     */
    private static final MethodHandle FIRST_INVOKE_MH;

    /**
     * {@link MethodHandle} to {@link TemplateBootstrap#defaultGuard}
     */
    private static final MethodHandle DEFAULT_GUARD_MH;

    /**
     * {@link MethodHandles.Lookup} passed to the bootstrap method
     */
    private final MethodHandles.Lookup lookup;

    /**
     * Name passed to the bootstrap method
     */
    private final String name;

    /**
     * {@link MethodType} passed to the bootstrap method
     */
    private final MethodType type;

    /**
     * Template stencil passed to the bootstrap method
     */
    private final String stencil;

    /**
     * {@link MethodHandle} to {@link TemplatePolicy} constant getter.
     */
    private final MethodHandle policyGetter;

    /**
     * Constructor.
     *
     * @param lookup       method lookup
     * @param name         method name
     * @param type         method type
     * @param stencil      stencil string with placeholders
     * @param policyGetter {@link MethodHandle} to get constant
     *                     {@link TemplatePolicy}
     */
    TemplateBootstrap(MethodHandles.Lookup lookup, String name,
                      MethodType type, String stencil,
                      MethodHandle policyGetter) {
        this.lookup = lookup;
        this.name = name;
        this.type = type;
        this.stencil = stencil;
        this.policyGetter = policyGetter;
    }

    /**
     * Templated string bootstrap method.
     *
     * @param lookup  method lookup
     * @param name    method name
     * @param type    method type
     * @param stencil stencil string with placeholders
     * @return {@link CallSite} to handle templated string processing
     * @throws NullPointerException if any of the arguments is null
     */
    public static CallSite templatedStringBSM(
            MethodHandles.Lookup lookup,
            String name,
            MethodType type,
            String stencil) {
        Objects.requireNonNull(lookup, "lookup is null");
        Objects.requireNonNull(name, "name is null");
        Objects.requireNonNull(type, "type is null");
        Objects.requireNonNull(stencil, "stencil is null");

        return templatedStringBSM(lookup, name, type, stencil, null);
    }

    /**
     * Templated string bootstrap method.
     *
     * @param lookup       method lookup
     * @param name         method name
     * @param type         method type
     * @param stencil      stencil string with placeholders
     * @param policyGetter {@link MethodHandle} to get constant
     *                     {@link TemplatePolicy}
     * @return {@link CallSite} to handle templated string processing
     * @throws NullPointerException if any of the arguments is null
     */
    public static CallSite templatedStringBSM(
            MethodHandles.Lookup lookup,
            String name,
            MethodType type,
            String stencil,
            MethodHandle policyGetter) {
        Objects.requireNonNull(lookup, "lookup is null");
        Objects.requireNonNull(name, "name is null");
        Objects.requireNonNull(type, "type is null");
        Objects.requireNonNull(stencil, "stencil is null");

        TemplateBootstrap bootstrap = new TemplateBootstrap(lookup, name, type,
                stencil, policyGetter);

        return bootstrap.callsite();
    }

    /**
     * Initialize {@link MethodHandle MethodHandles}
     */
    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            MethodType mt = MethodType.methodType(
                    void.class,
                    String.class,
                    List.class,
                    MethodHandle.class,
                    MethodHandle.class,
                    Object.class
            );
            TEMPLATED_STRING_CARRIER_MH = lookup.findConstructor(
                    TemplatedStringCarrier.class, mt);

            mt = MethodType.methodType(TemplatedString.class, String.class,
                    List.class, Object[].class);
            NEW_TEMPLATED_STRING = lookup.findStatic(TemplateBootstrap.class,
                    "newTemplatedString", mt);

            mt = MethodType.methodType(List.class, Object[].class);
            LIST_OF_MH = lookup.findStatic(TemplateBootstrap.class, "listOf", mt);

            mt = MethodType.methodType(Object.class, TemplatedString.class);
            APPLY_MH = lookup.findVirtual(TemplatePolicy.class, "apply", mt);

            mt = MethodType.methodType(Object.class, TemplateBootstrap.class,
                    MutableCallSite.class, Object[].class);
            FIRST_INVOKE_MH = lookup.findStatic(TemplateBootstrap.class,
                    "firstInvoke", mt);

            mt = MethodType.methodType(boolean.class, Class.class,
                    TemplatePolicy.class);
            DEFAULT_GUARD_MH = lookup.findStatic(TemplateBootstrap.class,
                    "defaultGuard", mt);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("templated string bootstrap fail", ex);
        }
    }

    /**
     * Bootstrap method selector.
     */
    private enum Selector {
        createTemplatedString,
        applyWithArray,
        applyWithPolicy,
        applyWithConstantPolicy
    }

    /**
     * Return a {@link CallSite} based on the selector.
     *
     * @return a {@link CallSite}
     */
    CallSite callsite() {
        return switch (Selector.valueOf(name)) {
            case createTemplatedString -> createTemplatedString();
            case applyWithArray -> applyWithArray();
            case applyWithPolicy -> applyWithPolicy();
            case applyWithConstantPolicy -> applyWithConstantPolicy();
        };
    }

    /**
     * Apply the carrier component getters to the arguments of the
     * supplied {@link MethodHandle}.
     *
     * @param components array of carrier component getters
     * @param mh         supplied {@link MethodHandle}
     * @return new {@link MethodHandle} with carrier as the only input
     */
    private MethodHandle componentFilters(MethodHandle[] components,
                                          MethodHandle mh) {
        mh = MethodHandles.filterArguments(mh, 0, components);
        int[] reorder = new int[components.length];
        MethodType mt = MethodType.methodType(mh.type().returnType(),
                Object.class);
        mh = MethodHandles.permuteArguments(mh, mt, reorder);

        return mh;
    }

    /**
     * Returns an immutable list built from an array of objects.
     *
     * @param values array of objects
     * @returns immutable list of objects
     */
    private static List<Object> listOf(Object[] values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    /**
     * Return a {@link MethodHandle} that constructs a list from the
     * values in a carrier.
     *
     * @param carrierType {@link MethodType} to component types (policy dropped)
     * @param components  array of carrier component getters
     * @return new {@link MethodHandle} with carrier as the only input and
     * list of values as output
     */
    private MethodHandle valuesMethodHandle(MethodType carrierType,
                                            MethodHandle[] components) {
        MethodHandle mh = LIST_OF_MH.asVarargsCollector(Object[].class);
        mh = mh.asType(carrierType.changeReturnType(List.class));

        return componentFilters(components, mh);
    }

    /**
     * Return a {@link MethodHandle} that constructs a concatenation from
     * the stencil and the values in a carrier.
     *
     * @param carrierType {@link MethodType} to component types (policy dropped)
     * @param components  array of carrier component getters
     * @return new {@link MethodHandle} with carrier as the only input and
     * a string as output
     */
    private MethodHandle concatMethodHandle(MethodType carrierType,
                                            MethodHandle[] components) {
        try {
            List<String> fragments = TemplatedString.split(stencil);

            return StringConcatFactory.makeConcatWithTemplateGetters(fragments,
                    List.of(components),
                    StringConcatFactory.MAX_TEMPLATE_CONCAT_ARG_SLOTS);
        } catch (StringConcatException ex) {
            throw new AssertionError("StringConcatFactory failure", ex);
        }
    }

    /**
     * Return {@link MethodHandle} that constructs a new
     * {@link TemplatedStringCarrier} object.
     *
     * @param carrierType {@link MethodType} to component types (policy dropped)
     * @return new {@link MethodHandle} with {@link CallSite} arguments as
     * input and a new {@link TemplatedStringCarrier} object as output.
     */
    private MethodHandle createTemplatedStringCarrier(MethodType carrierType) {
        CarrierElements elements = CarrierFactory.of(carrierType);
        MethodHandle constructor = elements.constructor();
        MethodHandle[] components = elements.components().toArray(new MethodHandle[0]);
        MethodHandle values = valuesMethodHandle(carrierType, components);
        MethodHandle concat = concatMethodHandle(carrierType, components);
        MethodHandle mh = MethodHandles.insertArguments(
                TEMPLATED_STRING_CARRIER_MH, 0, stencil,
                TemplatedString.split(stencil), values, concat);
        mh = MethodHandles.collectArguments(mh, 0, constructor);
        mh = mh.asType(mh.type().changeReturnType(TemplatedString.class));

        return mh;
    }

    /**
     * Generate a method which is effectively
     * {@code policy.apply(new TemplatedString(stencil, values...)}.
     *
     * @return default Method
     */
    private MethodHandle defaultMethodHandle() {
        MethodType carrierType = type.dropParameterTypes(0, 1);
        MethodHandle templatedString = carrierType.parameterCount() == 0 ?
                MethodHandles.constant(TemplatedString.class,
                        TemplatedString.of(stencil)) :
                createTemplatedStringCarrier(carrierType);
        MethodHandle mh = MethodHandles.collectArguments(APPLY_MH, 1,
                templatedString);
        mh = mh.asType(type);

        return mh;
    }

    /**
     * Guard to use when not supplied by policy.
     *
     * @param expectedClass expected policy class
     * @param policy        current policy
     * @return true if policy.getCLass() == expectedClass
     */
    private static boolean defaultGuard(Class<?> expectedClass,
                                        TemplatePolicy<?, ?> policy) {
        return expectedClass == policy.getClass();
    }

    /**
     * Assembles a {@link MethodHandle} from the guard and applier from
     * an {@link TemplatePolicy.PolicyLinkage policy}. The
     * default {@link MethodHandle} will be returned if
     * {@link TemplatePolicy.PolicyLinkage#applier} returns null. The default
     * guard will be used if {@link TemplatePolicy.PolicyLinkage#guard} returns
     * null.
     *
     * @param policyLinkage policy that supplies solution
     * @param withGuard     true if guard is required
     * @return assembled {@link MethodHandle}
     */
    private MethodHandle linkMethodHandle(PolicyLinkage policyLinkage,
                                          boolean withGuard) {
        MethodHandle applier = policyLinkage.applier(lookup, type, stencil);

        if (applier != null) {
            applier = applier.asType(type);

            if (withGuard) {
                MethodHandle guard = policyLinkage.guard(lookup, type, stencil);

                if (guard == null) {
                    guard = DEFAULT_GUARD_MH;
                    Class<?>[] others = type.dropParameterTypes(0, 1)
                            .parameterArray();
                    guard = MethodHandles.dropArguments(guard, 2, others);
                    guard = MethodHandles.insertArguments(guard, 0,
                            type.parameterType(0));
                }

                guard = guard.asType(type.changeReturnType(boolean.class));
                applier = MethodHandles.guardWithTest(guard, applier,
                        defaultMethodHandle());
            }

            return applier;
        }

        return defaultMethodHandle();
    }

    /**
     * {@return true if the supplied class is final}
     *
     * @param cls supplied class
     */
    private static boolean isFinal(Class<?> cls) {
        return (cls.getModifiers() & Modifier.FINAL) != 0;
    }

    /**
     * Method invoke at {@link MutableCallSite} the first time invoked
     * replacing the callsite target with an optimal solution.
     *
     * @param bootstrap {@link TemplateBootstrap} creating callsite
     * @param callsite  {@link MutableCallSite} to update
     * @param args      arguments to invoke
     * @return result of invocation
     */
    @SuppressWarnings("unchecked")
    private static Object firstInvoke(TemplateBootstrap bootstrap,
                                      MutableCallSite callsite,
                                      Object... args) throws Throwable {
        MethodHandle mh;
        TemplatePolicy<Object, Throwable> policy =
                TemplatePolicy.class.cast(args[0]);

        if (policy instanceof PolicyLinkage policyLinkage) {
            boolean needsGuard = !isFinal(bootstrap.type.parameterType(0));
            mh = bootstrap.linkMethodHandle(policyLinkage, needsGuard);
        } else {
            mh = bootstrap.defaultMethodHandle();
        }

        callsite.setTarget(mh);
        MutableCallSite.syncAll(new MutableCallSite[]{callsite});

        return mh.invokeWithArguments(args);
    }

    /**
     * Selector for no policy {@link CallSite CallSites}.
     *
     * @return {@link CallSite} for handling no policy templated strings.
     */
    private CallSite createTemplatedString() {
        MethodHandle mh = type.parameterCount() == 0 ?
                MethodHandles.constant(TemplatedString.class,
                        TemplatedString.of(stencil)) :
                createTemplatedStringCarrier(type);

        return new ConstantCallSite(mh);
    }

    /**
     * Selector for {@link Object} array. Needed when slot count exceeds 240.
     */
    private CallSite applyWithArray() {
        MethodType mt = MethodType.methodType(TemplatedString.class,
                Object[].class);
        MethodHandle mh = MethodHandles.insertArguments(
                NEW_TEMPLATED_STRING, 0, stencil,
                TemplatedString.split(stencil)).asType(mt);

        if (type.parameterCount() == 2) {
            mh = MethodHandles.filterArguments(APPLY_MH, 1, mh);
        }

        return new ConstantCallSite(mh.asType(type));
    }

    /**
     * Selector for policy {@link CallSite CallSites}.
     *
     * @return {@link CallSite} for handling apply policy templated strings.
     */
    private CallSite applyWithPolicy() {
        MutableCallSite callSite = new MutableCallSite(type);
        MethodHandle mh = MethodHandles.insertArguments(FIRST_INVOKE_MH,
                0, this, callSite);
        mh = mh.withVarargs(true);
        mh = mh.asType(type);
        callSite.setTarget(mh);

        return callSite;
    }

    /**
     * Selector for apply constant policy {@link CallSite CallSites}.
     *
     * @return {@link CallSite} for handling apply constant policy
     * templated strings.
     */
    private CallSite applyWithConstantPolicy() {
        try {
            TemplatePolicy<Object, Throwable> policy =
                    (TemplatePolicy<Object, Throwable>)policyGetter.invoke();

            if (policy instanceof PolicyLinkage policyLinkage) {
                MethodHandle mh = linkMethodHandle(policyLinkage, false);

                return new ConstantCallSite(mh);
            }
        } catch (Throwable ex) {
            throw new AssertionError("templated string bootstrap fail", ex);
        }

        return applyWithPolicy();
    }

    /**
     * Implementation of {@link TemplatedString} used to wrap components
     * from a templated string {@link CallSite} in a carrier object.
     */
    private static class TemplatedStringCarrier implements TemplatedString {
        /**
         * Stencil string with placeholders.
         */
        @Stable
        private final String stencil;

        /**
         * {@link MethodHandle} to method to produce a list of expression
         * values.
         */
        @Stable
        private final MethodHandle values;

        /**
         * List of string fragments from splitting the stencil at placeholders.
         */
        @Stable
        private final List<String> fragments;

        /**
         * {@link MethodHandle} to method to produce concatenation.
         */
        @Stable
        private final MethodHandle concat;

        /**
         * Carrier object.
         */
        @Stable
        private final Object carrier;

        /**
         * Constructor.
         *
         * @param stencil   stencil string with placeholders
         * @param fragments List of string fragments from splitting the stencil at
         *                  placeholders
         * @param values    {@link MethodHandle} to create value list from
         *                  carrier
         * @param concat    {@link MethodHandle} to perform concatenation from
         *                  carrier
         * @param carrier   carrier object containing values from {@link CallSite}
         */
        TemplatedStringCarrier(String stencil,
                               List<String> fragments,
                               MethodHandle values,
                               MethodHandle concat,
                               Object carrier) {
            this.stencil = stencil;
            this.values = values;
            this.fragments = fragments;
            this.concat = concat;
            this.carrier = carrier;
        }

        @Override
        public String stencil() {
            return stencil;
        }

        @Override
        public List<Object> values() {
            try {
                return (List<Object>)values.invokeExact(carrier);
            } catch (Throwable ex) {
                throw new AssertionError("carrier values fail", ex);
            }
        }

        @Override
        public List<String> fragments() {
            return fragments;
        }

        @Override
        public String concat() {
            try {
                return (String)concat.invokeExact(carrier);
            } catch (Throwable ex) {
                throw new AssertionError("carrier concat fail", ex);
            }
        }

        @Override
        public String toString() {
            return TemplatedString.toString(this);
        }
    }

    /**
     * Create a new instance of {@link TemplatedString}.
     *
     * @param stencil   stencil string with placeholders
     * @param fragments List of string fragments from splitting the stencil at
     *                  placeholders
     * @param values    {@link Object} array of expression values
     *
     * @return new TemplatedString instance
     */
    private static TemplatedString newTemplatedString(String stencil,
                                                      List<String> fragments,
                                                      Object[] values) {
        return JLA.newTemplatedString(stencil,
                Collections.unmodifiableList(Arrays.asList(values)), fragments);
    }

}
