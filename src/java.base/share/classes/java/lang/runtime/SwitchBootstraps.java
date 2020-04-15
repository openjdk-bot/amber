/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Bootstrap methods for linking {@code invokedynamic} call sites that implement
 * the selection functionality of the {@code switch} statement.  The bootstraps
 * take additional static arguments corresponding to the {@code case} labels
 * of the {@code switch}, implicitly numbered sequentially from {@code [0..N)}.
 *
 * <p>The bootstrap call site accepts a single parameter of the type of the
 * operand of the {@code switch}, and return an {@code int} that is the index of
 * the matched {@code case} label, {@code -1} if the target is {@code null},
 * or {@code N} if the target is not null but matches no {@code case} label.
 */
public class SwitchBootstraps {

    // Shared INIT_HOOK for all switch call sites; looks the target method up in a map
    private static final MethodHandle CONSTANT_INIT_HOOK;
    private static final MethodHandle PATTERN_INIT_HOOK;
    private static final MethodHandle TYPE_INIT_HOOK;
    private static final MethodHandle PATTERN_SWITCH_METHOD;
    private static final MethodHandle TYPE_SWITCH_METHOD;
    private static final Map<Class<?>, MethodHandle> switchMethods = new ConcurrentHashMap<>();

    private static final Set<Class<?>> BOOLEAN_TYPES
            = Set.of(boolean.class, Boolean.class);
    // Types that can be handled as int switches
    private static final Set<Class<?>> INT_TYPES
            = Set.of(int.class, short.class, byte.class, char.class,
                     Integer.class, Short.class, Byte.class, Character.class);
    private static final Set<Class<?>> FLOAT_TYPES
            = Set.of(float.class, Float.class);
    private static final Set<Class<?>> LONG_TYPES
            = Set.of(long.class, Long.class);
    private static final Set<Class<?>> DOUBLE_TYPES
            = Set.of(double.class, Double.class);

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Function<Class<?>, MethodHandle> lookupSwitchMethod =
            new Function<>() {
                @Override
                public MethodHandle apply(Class<?> c) {
                    try {
                        Class<?> switchClass;
                        if (c == Enum.class)
                            switchClass = EnumSwitchCallSite.class;
                        else if (c == String.class)
                            switchClass = StringSwitchCallSite.class;
                        else if (BOOLEAN_TYPES.contains(c) || INT_TYPES.contains(c) ||
                                 FLOAT_TYPES.contains(c))
                            switchClass = IntSwitchCallSite.class;
                        else if (LONG_TYPES.contains(c) || DOUBLE_TYPES.contains(c))
                            switchClass = LongSwitchCallSite.class;
                        else if (c == Object.class)
                            switchClass = TypeSwitchCallSite.class;
                        else
                            throw new BootstrapMethodError("Invalid switch type: " + c);

                        return LOOKUP.findVirtual(switchClass, "doSwitch",
                                                  MethodType.methodType(int.class, c));
                    }
                    catch (ReflectiveOperationException e) {
                        throw new BootstrapMethodError("Invalid switch type: " + c);
                    }
                }
            };

    static {
        try {
            CONSTANT_INIT_HOOK = LOOKUP.findStatic(SwitchBootstraps.class, "constantInitHook",
                                                   MethodType.methodType(MethodHandle.class, CallSite.class));
            PATTERN_INIT_HOOK = LOOKUP.findStatic(SwitchBootstraps.class, "patternInitHook",
                                                  MethodType.methodType(MethodHandle.class, CallSite.class));
            TYPE_INIT_HOOK = LOOKUP.findStatic(SwitchBootstraps.class, "typeInitHook",
                                                  MethodType.methodType(MethodHandle.class, CallSite.class));
            PATTERN_SWITCH_METHOD = LOOKUP.findVirtual(PatternSwitchCallSite.class, "doSwitch",
                                                       MethodType.methodType(PatternSwitchResult.class, Object.class));
            TYPE_SWITCH_METHOD = LOOKUP.findVirtual(TypeSwitchCallSite.class, "doSwitch",
                                                    MethodType.methodType(int.class, Object.class));
        }
        catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static<T extends CallSite> MethodHandle constantInitHook(T receiver) {
        return switchMethods.computeIfAbsent(receiver.type().parameterType(0), lookupSwitchMethod)
                            .bindTo(receiver);
    }

    private static<T extends CallSite> MethodHandle typeInitHook(T receiver) {
        return TYPE_SWITCH_METHOD.bindTo(receiver);
    }

    private static<T extends CallSite> MethodHandle patternInitHook(T receiver) {
        return PATTERN_SWITCH_METHOD.bindTo(receiver);
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on a {@code boolean} or {@code Boolean}.
     * The static arguments are a varargs array of {@code boolean} labels,
     *
     * <p>The results are undefined if the labels array contains duplicates.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter which is
     *                       {@code boolean} or {@code Boolean},and return {@code int}.
     *                       When used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param booleanLabels boolean values corresponding to the case labels of the
     *                  {@code switch} statement.
     * @return the index into {@code booleanLabels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code booleanLabels.length} if the target value does
     *         not match any of the labels.
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if the invocation type is not
     * {@code (boolean)int} or {@code (Boolean)int}
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite booleanSwitch(MethodHandles.Lookup lookup,
                                         String invocationName,
                                         MethodType invocationType,
                                         boolean... booleanLabels) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!BOOLEAN_TYPES.contains(invocationType.parameterType(0))))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(booleanLabels);

        int[] intLabels = IntStream.range(0, booleanLabels.length)
                                   .map(i -> booleanLabels[i] ? 1 : 0)
                                   .toArray();

        assert IntStream.of(intLabels).distinct().count() == intLabels.length
                : "switch labels are not distinct: " + Arrays.toString(booleanLabels);

        return new IntSwitchCallSite(invocationType, intLabels);
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on an {@code int}, {@code short}, {@code byte},
     * {@code char}, or one of their box types.  The static arguments are a
     * varargs array of {@code int} labels.
     *
     * <p>The results are undefined if the labels array contains duplicates.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter which is
     *                       one of the 32-bit or shorter primitive types, or
     *                       one of their box types, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param intLabels integral values corresponding to the case labels of the
     *                  {@code switch} statement.
     * @return the index into {@code intLabels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code intLabels.length} if the target value does
     *         not match any of the labels.
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if the invocation type is not
     * {@code (T)int}, where {@code T} is one of the 32-bit or smaller integral
     * primitive types, or one of their box types
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite intSwitch(MethodHandles.Lookup lookup,
                                     String invocationName,
                                     MethodType invocationType,
                                     int... intLabels) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!INT_TYPES.contains(invocationType.parameterType(0))))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(intLabels);

        assert IntStream.of(intLabels).distinct().count() == intLabels.length
                : "switch labels are not distinct: " + Arrays.toString(intLabels);

        return new IntSwitchCallSite(invocationType, intLabels);
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on an {@code float} or {@code Float}.
     * The static arguments are a varargs array of {@code float} labels.
     *
     * <p>The results are undefined if the labels array contains duplicates
     * according to {@link Float#floatToIntBits(float)}.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter which is
     *                       one of the 32-bit or shorter primitive types, or
     *                       one of their box types, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param floatLabels float values corresponding to the case labels of the
     *                    {@code switch} statement.
     * @return the index into {@code floatLabels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code floatLabels.length} if the target value does
     *         not match any of the labels.
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if the invocation type is not
     * {@code (float)int} or {@code (Float)int}
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite floatSwitch(MethodHandles.Lookup lookup,
                                       String invocationName,
                                       MethodType invocationType,
                                       float... floatLabels) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!FLOAT_TYPES.contains(invocationType.parameterType(0))))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(floatLabels);

        int[] intLabels = new int[floatLabels.length];
        for (int i=0; i<floatLabels.length; i++)
            intLabels[i] = Float.floatToIntBits(floatLabels[i]);

        assert IntStream.of(intLabels).distinct().count() == intLabels.length
                : "switch labels are not distinct: " + Arrays.toString(floatLabels);

        return new IntSwitchCallSite(invocationType, intLabels);
    }

    static class IntSwitchCallSite extends ConstantCallSite {
        private final int[] labels;
        private final int[] indexes;

        IntSwitchCallSite(MethodType targetType,
                          int[] intLabels) throws Throwable {
            super(targetType, CONSTANT_INIT_HOOK);

            // expensive way to index an array
            indexes = IntStream.range(0, intLabels.length)
                               .boxed()
                               .sorted(Comparator.comparingInt(a -> intLabels[a]))
                               .mapToInt(Integer::intValue)
                               .toArray();
            labels = new int[indexes.length];
            for (int i=0; i<indexes.length; i++)
                labels[i] = intLabels[indexes[i]];
        }

        int doSwitch(int target) {
            int index = Arrays.binarySearch(labels, target);
            return (index >= 0) ? indexes[index] : indexes.length;
        }

        int doSwitch(boolean target) {
            return doSwitch(target ? 1 : 0);
        }

        int doSwitch(float target) {
            return doSwitch(Float.floatToIntBits(target));
        }

        int doSwitch(short target) {
            return doSwitch((int) target);
        }

        int doSwitch(byte target) {
            return doSwitch((int) target);
        }

        int doSwitch(char target) {
            return doSwitch((int) target);
        }

        int doSwitch(Boolean target) {
            return (target == null) ? -1 : doSwitch((boolean) target);
        }

        int doSwitch(Integer target) {
            return (target == null) ? -1 : doSwitch((int) target);
        }

        int doSwitch(Float target) {
            return (target == null) ? -1 : doSwitch((float) target);
        }

        int doSwitch(Short target) {
            return (target == null) ? -1 : doSwitch((int) target);
        }

        int doSwitch(Character target) {
            return (target == null) ? -1 : doSwitch((int) target);
        }

        int doSwitch(Byte target) {
            return (target == null) ? -1 : doSwitch((int) target);
        }
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on a {@code long} or {@code Long}.
     * The static arguments are a varargs array of {@code long} labels.
     *
     * <p>The results are undefined if the labels array contains duplicates.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter which is
     *                       one of the 32-bit or shorter primitive types, or
     *                       one of their box types, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param longLabels long values corresponding to the case labels of the
     *                  {@code switch} statement.
     * @return the index into {@code longLabels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code longLabels.length} if the target value does
     *         not match any of the labels.
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if the invocation type is not
     * {@code (long)int} or {@code (Long)int}
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite longSwitch(MethodHandles.Lookup lookup,
                                      String invocationName,
                                      MethodType invocationType,
                                      long... longLabels) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!LONG_TYPES.contains(invocationType.parameterType(0))))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(longLabels);

        assert LongStream.of(longLabels).distinct().count() == longLabels.length
                : "switch labels are not distinct: " + Arrays.toString(longLabels);

        return new LongSwitchCallSite(invocationType, longLabels);
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on a {@code double} or {@code Double}.
     * The static arguments are a varargs array of {@code double} labels.
     *
     * <p>The results are undefined if the labels array contains duplicates
     * according to {@link Double#doubleToLongBits(double)}.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter which is
     *                       one of the 32-bit or shorter primitive types, or
     *                       one of their box types, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param doubleLabels long values corresponding to the case labels of the
     *                  {@code switch} statement.
     * @return the index into {@code doubleLabels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code doubleLabels.length} if the target value does
     *         not match any of the labels.
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if the invocation type is not
     * {@code (double)int} or {@code (Double)int}
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite doubleSwitch(MethodHandles.Lookup lookup,
                                        String invocationName,
                                        MethodType invocationType,
                                        double... doubleLabels) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!DOUBLE_TYPES.contains(invocationType.parameterType(0))))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(doubleLabels);

        long[] longLabels = new long[doubleLabels.length];
        for (int i=0; i<doubleLabels.length; i++)
            longLabels[i] = Double.doubleToLongBits(doubleLabels[i]);

        assert LongStream.of(longLabels).distinct().count() == longLabels.length
                : "switch labels are not distinct: " + Arrays.toString(doubleLabels);

        return new LongSwitchCallSite(invocationType, longLabels);
    }

    static class LongSwitchCallSite extends ConstantCallSite {
        private final long[] labels;
        private final int[] indexes;

        LongSwitchCallSite(MethodType targetType,
                           long[] longLabels) throws Throwable {
            super(targetType, CONSTANT_INIT_HOOK);

            // expensive way to index an array
            indexes = IntStream.range(0, longLabels.length)
                               .boxed()
                               .sorted(Comparator.comparingLong(a -> longLabels[a]))
                               .mapToInt(Integer::intValue)
                               .toArray();
            labels = new long[indexes.length];
            for (int i=0; i<indexes.length; i++)
                labels[i] = longLabels[indexes[i]];
        }

        int doSwitch(long target) {
            int index = Arrays.binarySearch(labels, target);
            return (index >= 0) ? indexes[index] : indexes.length;
        }

        int doSwitch(double target) {
            return doSwitch(Double.doubleToLongBits(target));
        }

        int doSwitch(Long target) {
            return (target == null) ? -1 : doSwitch((long) target);
        }

        int doSwitch(Double target) {
            return (target == null) ? -1 : doSwitch((double) target);
        }
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on a {@code String} target.  The static
     * arguments are a varargs array of {@code String} labels.
     *
     * <p>The results are undefined if the labels array contains duplicates
     * according to {@link String#equals(Object)}.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter of
     *                       {@code String}, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param stringLabels non-null string values corresponding to the case
     *                     labels of the {@code switch} statement.
     * @return the index into {@code labels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code stringLabels.length} if the target value
     *         does not match any of the labels.
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if any labels are null, or if the
     * invocation type is not {@code (String)int}
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite stringSwitch(MethodHandles.Lookup lookup,
                                        String invocationName,
                                        MethodType invocationType,
                                        String... stringLabels) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!invocationType.parameterType(0).equals(String.class)))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(stringLabels);
        if (Stream.of(stringLabels).anyMatch(Objects::isNull))
            throw new IllegalArgumentException("null label found");

        assert Stream.of(stringLabels).distinct().count() == stringLabels.length
                : "switch labels are not distinct: " + Arrays.toString(stringLabels);

        return new StringSwitchCallSite(invocationType, stringLabels);
    }

    static class StringSwitchCallSite extends ConstantCallSite {
        private static final Comparator<String> STRING_BY_HASH
                = Comparator.comparingInt(Objects::hashCode);

        private final String[] sortedByHash;
        private final int[] indexes;
        private final boolean collisions;

        StringSwitchCallSite(MethodType targetType,
                             String[] stringLabels) throws Throwable {
            super(targetType, CONSTANT_INIT_HOOK);

            // expensive way to index an array
            indexes = IntStream.range(0, stringLabels.length)
                               .boxed()
                               .sorted(Comparator.comparingInt(i -> stringLabels[i].hashCode()))
                               .mapToInt(Integer::intValue)
                               .toArray();
            sortedByHash = new String[indexes.length];
            for (int i=0; i<indexes.length; i++)
                sortedByHash[i] = stringLabels[indexes[i]];

            collisions = IntStream.range(0, sortedByHash.length-1)
                                  .anyMatch(i -> sortedByHash[i].hashCode() == sortedByHash[i + 1].hashCode());
        }

        int doSwitch(String target) {
            if (target == null)
                return -1;

            int index = Arrays.binarySearch(sortedByHash, target, STRING_BY_HASH);
            if (index < 0)
                return indexes.length;
            else if (target.equals(sortedByHash[index])) {
                return indexes[index];
            }
            else if (collisions) {
                int hash = target.hashCode();
                while (index > 0 && sortedByHash[index-1].hashCode() == hash)
                    --index;
                for (; index < sortedByHash.length && sortedByHash[index].hashCode() == hash; index++)
                    if (target.equals(sortedByHash[index]))
                        return indexes[index];
            }

            return indexes.length;
        }
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on an {@code Enum} target.  The static
     * arguments are the enum class, and a varargs arrays of {@code String}
     * that are the names of the enum constants corresponding to the
     * {@code case} labels.
     *
     * <p>The results are undefined if the names array contains duplicates.
     *
     * @implNote
     *
     * The implementation only enforces the requirement that the labels array
     * be duplicate-free if system assertions are enabled.
     *
     * @param <E> the enum type
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter of
     *                       {@code Enum}, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param enumClass the enum class
     * @param enumNames names of the enum constants against which the target
     *                  should be matched
     * @return the index into {@code labels} of the target value, if the target
     *         matches any of the labels, {@literal -1} if the target value is
     *         {@code null}, or {@code stringLabels.length} if the target value
     *         does not match any of the labels.
     * @throws IllegalArgumentException if the specified class is not an
     *                                  enum class, or any label name is null,
     *                                  or if the invocation type is not
     *                                  {@code (Enum)int}
     * @throws NullPointerException if any required argument is null
     * @throws Throwable if there is any error linking the call site
     */
    public static<E extends Enum<E>> CallSite enumSwitch(MethodHandles.Lookup lookup,
                                                         String invocationName,
                                                         MethodType invocationType,
                                                         Class<E> enumClass,
                                                         String... enumNames) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || (!invocationType.parameterType(0).equals(Enum.class)))
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(enumClass);
        requireNonNull(enumNames);
        if (!enumClass.isEnum())
            throw new IllegalArgumentException("not an enum class");
        if (Stream.of(enumNames).anyMatch(Objects::isNull))
            throw new IllegalArgumentException("null label found");

        assert Stream.of(enumNames).distinct().count() == enumNames.length
                : "switch labels are not distinct: " + Arrays.toString(enumNames);

        return new EnumSwitchCallSite<>(invocationType, enumClass, enumNames);
    }

    static class EnumSwitchCallSite<E extends Enum<E>> extends ConstantCallSite {
        private final int[] ordinalMap;

        EnumSwitchCallSite(MethodType targetType,
                           Class<E> enumClass,
                           String... enumNames) throws Throwable {
            super(targetType, CONSTANT_INIT_HOOK);

            ordinalMap = new int[enumClass.getEnumConstants().length];
            Arrays.fill(ordinalMap, enumNames.length);

            for (int i=0; i<enumNames.length; i++) {
                try {
                    ordinalMap[E.valueOf(enumClass, enumNames[i]).ordinal()] = i;
                }
                catch (Exception e) {
                    // allow non-existent labels, but never match them
                    continue;
                }
            }
        }

        @SuppressWarnings("rawtypes")
        int doSwitch(Enum target) {
            return (target == null) ? -1 : ordinalMap[target.ordinal()];
        }
    }

    /**
     * Bootstrap method for linking an {@code invokedynamic} call site that
     * implements a {@code switch} on a reference-typed target.  The static
     * arguments are a varargs array of {@code Class} labels.
     *
     * @param lookup Represents a lookup context with the accessibility
     *               privileges of the caller.  When used with {@code invokedynamic},
     *               this is stacked automatically by the VM.
     * @param invocationName The invocation name, which is ignored.  When used with
     *                       {@code invokedynamic}, this is provided by the
     *                       {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param invocationType The invocation type of the {@code CallSite}.  This
     *                       method type should have a single parameter of
     *                       a reference type, and return {@code int}.  When
     *                       used with {@code invokedynamic}, this is provided by
     *                       the {@code NameAndType} of the {@code InvokeDynamic}
     *                       structure and is stacked automatically by the VM.
     * @param types non-null {@link Class} values
     * @return the index into {@code labels} of the target value, if the target
     *         is an instance of any of the types, {@literal -1} if the target
     *         value is {@code null}, or {@code types.length} if the target value
     *         is not an instance of any of the types
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if any labels are null, or if the
     * invocation type is not {@code (T)int for some reference type {@code T}}
     * @throws Throwable if there is any error linking the call site
     */
    public static CallSite typeSwitch(MethodHandles.Lookup lookup,
                                      String invocationName,
                                      MethodType invocationType,
                                      Class<?>... types) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(int.class))
            || invocationType.parameterType(0).isPrimitive())
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(types);

        types = types.clone();
        if (Stream.of(types).anyMatch(Objects::isNull))
            throw new IllegalArgumentException("null label found");

        assert Stream.of(types).distinct().count() == types.length
                : "switch labels are not distinct: " + Arrays.toString(types);

        return new TypeSwitchCallSite(invocationType, types);
    }

    static class TypeSwitchCallSite extends ConstantCallSite {
        private final Class<?>[] types;

        TypeSwitchCallSite(MethodType targetType,
                           Class<?>[] types) throws Throwable {
            super(targetType, TYPE_INIT_HOOK);
            this.types = types;
        }

        int doSwitch(Object target) {
            if (target == null)
                return -1;

            // Dumbest possible strategy
            Class<?> targetClass = target.getClass();
            for (int i = 0; i < types.length; i++) {
                Class<?> c = types[i];
                if (c.isAssignableFrom(targetClass))
                    return i;
            }

            return types.length;
        }
    }

    /**
     * Result type for pattern switches
     */
    public static class PatternSwitchResult {
        /**
         * The selected index, -1 if input was null, or length if not matched
         */
        public final int index;

        /**
         * The carrier
         */
        public final Object carrier;

        /**
         * Construct a PatternSwitchResult
         *
         * @param index the index
         * @param carrier the carrier
         */
        public PatternSwitchResult(int index, Object carrier) {
            this.index = index;
            this.carrier = carrier;
        }
    }

    /**
     * Bootstrap for pattern switches
     *
     * @param lookup the lookup (ignored)
     * @param invocationName the invocation name (ignored)
     * @param invocationType the invocation type (must return PatternSwitchResult)
     * @param patterns the patterns
     * @return the result
     * @throws Throwable if something went wrong
     */
    public static CallSite patternSwitch(MethodHandles.Lookup lookup,
                                         String invocationName,
                                         MethodType invocationType,
                                         PatternHandle... patterns) throws Throwable {
        if (invocationType.parameterCount() != 1
            || (!invocationType.returnType().equals(PatternSwitchResult.class))
            || invocationType.parameterType(0).isPrimitive())
            throw new IllegalArgumentException("Illegal invocation type " + invocationType);
        requireNonNull(patterns);

        patterns = patterns.clone();
        Class<?> targetType = invocationType.parameterType(0);

        for (int i = 0; i < patterns.length; i++) {
            PatternHandle pattern = patterns[i];
            if (pattern.descriptor().returnType() != targetType)
                patterns[i] = PatternHandles.adaptTarget(pattern, targetType);
        }

        if (Stream.of(patterns).anyMatch(Objects::isNull))
            throw new IllegalArgumentException("null pattern found");

        return new PatternSwitchCallSite(invocationType, patterns);
    }

    static class PatternSwitchCallSite extends ConstantCallSite {
        private final PatternHandle[] patterns;

        PatternSwitchCallSite(MethodType targetType,
                              PatternHandle[] patterns) throws Throwable {
            super(targetType, PATTERN_INIT_HOOK);
            this.patterns = patterns;
        }

        PatternSwitchResult doSwitch(Object target) throws Throwable {
            if (target == null)
                return new PatternSwitchResult(-1, null);

            // Dumbest possible strategy
            for (int i = 0; i < patterns.length; i++) {
                PatternHandle e = patterns[i];
                Object o = e.tryMatch().invoke(target);
                if (o != null)
                    return new PatternSwitchResult(i, o);
            }

            return new PatternSwitchResult(patterns.length, null);

        }
    }
}
