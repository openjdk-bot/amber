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
package jdk.internal.classfile.impl.verifier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import jdk.internal.classfile.ClassHierarchyResolver;
import jdk.internal.classfile.ClassModel;
import jdk.internal.classfile.components.ClassPrinter;
import jdk.internal.classfile.Classfile;
import jdk.internal.classfile.impl.ClassHierarchyImpl;
import jdk.internal.classfile.impl.RawBytecodeHelper;
import static jdk.internal.classfile.impl.RawBytecodeHelper.ILLEGAL;
import jdk.internal.classfile.impl.verifier.VerificationWrapper.ConstantPoolWrapper;
import static jdk.internal.classfile.impl.verifier.VerificationSignature.BasicType.*;
import jdk.internal.classfile.impl.verifier.VerificationSignature.BasicType;
import static jdk.internal.classfile.impl.verifier.VerificationFrame.FLAG_THIS_UNINIT;

/**
 * @see <a href="https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/native/include/classfile_constants.h.template">java.base/share/native/include/classfile_constants.h.template</a>
 * @see <a href="https://raw.githubusercontent.com/openjdk/jdk/master/src/hotspot/share/classfile/verifier.hpp">hotspot/share/classfile/verifier.hpp</a>
 * @see <a href="https://raw.githubusercontent.com/openjdk/jdk/master/src/hotspot/share/classfile/verifier.cpp">hotspot/share/classfile/verifier.cpp</a>
 */
public final class VerifierImpl {
    static final int
            JVM_CONSTANT_Utf8                   = 1,
            JVM_CONSTANT_Unicode                = 2,
            JVM_CONSTANT_Integer                = 3,
            JVM_CONSTANT_Float                  = 4,
            JVM_CONSTANT_Long                   = 5,
            JVM_CONSTANT_Double                 = 6,
            JVM_CONSTANT_Class                  = 7,
            JVM_CONSTANT_String                 = 8,
            JVM_CONSTANT_Fieldref               = 9,
            JVM_CONSTANT_Methodref              = 10,
            JVM_CONSTANT_InterfaceMethodref     = 11,
            JVM_CONSTANT_NameAndType            = 12,
            JVM_CONSTANT_MethodHandle           = 15,
            JVM_CONSTANT_MethodType             = 16,
            JVM_CONSTANT_Dynamic                = 17,
            JVM_CONSTANT_InvokeDynamic          = 18,
            JVM_CONSTANT_Module                 = 19,
            JVM_CONSTANT_Package                = 20,
            JVM_CONSTANT_ExternalMax            = 20;

static final char JVM_SIGNATURE_SPECIAL = '<',
            JVM_SIGNATURE_ARRAY = '[',
            JVM_SIGNATURE_BYTE = 'B',
            JVM_SIGNATURE_CHAR = 'C',
            JVM_SIGNATURE_CLASS = 'L',
            JVM_SIGNATURE_FLOAT = 'F',
            JVM_SIGNATURE_DOUBLE = 'D',
            JVM_SIGNATURE_INT = 'I',
            JVM_SIGNATURE_LONG = 'J',
            JVM_SIGNATURE_SHORT = 'S',
            JVM_SIGNATURE_BOOLEAN = 'Z';

    static final String java_lang_String = "java/lang/String";
    static final String object_initializer_name = "<init>";
    static final String java_lang_invoke_MethodHandle = "java/lang/invoke/MethodHandle";
    static final String java_lang_Object = "java/lang/Object";
    static final String java_lang_invoke_MethodType = "java/lang/invoke/MethodType";
    static final String java_lang_Throwable = "java/lang/Throwable";
    static final String java_lang_Class = "java/lang/Class";

    String errorContext = "";
    private int bci;

    static void log_info(Consumer<String> logger, String messageFormat, Object... args) {
        if (logger != null) logger.accept(String.format(messageFormat + "%n", args));
    }
    private final Consumer<String> _logger;
    void log_info(String messageFormat, Object... args) {
        log_info(_logger, messageFormat, args);
    }


    static final int STACKMAP_ATTRIBUTE_MAJOR_VERSION = 50;
    static final int INVOKEDYNAMIC_MAJOR_VERSION = 51;
    static final int NOFAILOVER_MAJOR_VERSION = 51;

    public static List<VerifyError> verify(ClassModel classModel, Consumer<String> logger) {
        return verify(classModel, ClassHierarchyResolver.DEFAULT_CLASS_HIERARCHY_RESOLVER, logger);
    }

    public static List<VerifyError> verify(ClassModel classModel, ClassHierarchyResolver classHierarchyResolver, Consumer<String> logger) {
        var klass = new VerificationWrapper(classModel);
        if (!is_eligible_for_verification(klass)) {
            return List.of();
        }
        log_info(logger, "Start class verification for: %s", klass.thisClassName());
        try {
            if (klass.majorVersion() >= STACKMAP_ATTRIBUTE_MAJOR_VERSION) {
                var errors = new VerifierImpl(klass, classHierarchyResolver, logger).verify_class();
                if (!errors.isEmpty() && klass.majorVersion() < NOFAILOVER_MAJOR_VERSION) {
                    log_info(logger, "Fail over class verification to old verifier for: %s", klass.thisClassName());
                    return inference_verify(klass);
                } else {
                    return errors;
                }
            } else {
                return inference_verify(klass);
            }
        } finally {
            log_info(logger, "End class verification for: %s", klass.thisClassName());
        }
    }

    public static boolean is_eligible_for_verification(VerificationWrapper klass) {
        String name = klass.thisClassName();
        return !java_lang_Object.equals(name) &&
                !java_lang_Class.equals(name) &&
                !java_lang_String.equals(name) &&
                !java_lang_Throwable.equals(name);
    }

    static List<VerifyError> inference_verify(VerificationWrapper klass) {
        return List.of(new VerifyError("Inference verification is not supported"));
    }

    static class sig_as_verification_types {
        private int _num_args;
        private ArrayList<VerificationType> _sig_verif_types;

        sig_as_verification_types(ArrayList<VerificationType> sig_verif_types) {
            this._sig_verif_types = sig_verif_types;
            this._num_args = 0;
        }

        int num_args() {
            return _num_args;
        }

        void set_num_args(int num_args) {
            _num_args = num_args;
        }

        ArrayList<VerificationType> sig_verif_types() {
            return _sig_verif_types;
        }
    }

    VerificationType cp_ref_index_to_type(int index, ConstantPoolWrapper cp) {
        return cp_index_to_type(cp.refClassIndexAt(index), cp);
    }

    final VerificationWrapper _klass;
    final ClassHierarchyImpl _class_hierarchy;
    VerificationWrapper.MethodWrapper _method;
    VerificationType _this_type;

    static final int BYTECODE_OFFSET = 1, NEW_OFFSET = 2;

    VerificationWrapper current_class() {
        return _klass;
    }

    ClassHierarchyImpl class_hierarchy() {
        return _class_hierarchy;
    }

    VerificationType current_type() {
        return _this_type;
    }

    VerificationType cp_index_to_type(int index, ConstantPoolWrapper cp) {
        return VerificationType.reference_type(cp.classNameAt(index));
    }

    int change_sig_to_verificationType(VerificationSignature sig_type, VerificationType inference_types[], int inference_type_index) {
        BasicType bt = sig_type.type();
        switch (bt) {
            case T_OBJECT:
            case T_ARRAY:
                String name = sig_type.asSymbol();
                inference_types[inference_type_index] = VerificationType.reference_type(name);
                return 1;
            case T_LONG:
                inference_types[inference_type_index] = VerificationType.long_type;
                inference_types[++inference_type_index] = VerificationType.long2_type;
                return 2;
            case T_DOUBLE:
                inference_types[inference_type_index] = VerificationType.double_type;
                inference_types[++inference_type_index] = VerificationType.double2_type;
                return 2;
            case T_INT:
            case T_BOOLEAN:
            case T_BYTE:
            case T_CHAR:
            case T_SHORT:
                inference_types[inference_type_index] = VerificationType.integer_type;
                return 1;
            case T_FLOAT:
                inference_types[inference_type_index] = VerificationType.float_type;
                return 1;
            default:
                verifyError("Should not reach here");
                return 1;
        }
    }

    private static final int NONZERO_PADDING_BYTES_IN_SWITCH_MAJOR_VERSION = 51;
    private static final int STATIC_METHOD_IN_INTERFACE_MAJOR_VERSION = 52;
    private static final int MAX_ARRAY_DIMENSIONS = 255;

    VerifierImpl(VerificationWrapper klass, ClassHierarchyResolver classHierarchyResolver, Consumer<String> logger) {
        _klass = klass;
        _class_hierarchy = new ClassHierarchyImpl(classHierarchyResolver);
        _this_type = VerificationType.reference_type(klass.thisClassName());
        _logger = logger;
    }

    private VerificationType object_type() {
        return VerificationType.reference_type(java_lang_Object);
    }

    List<VerifyError> verify_class() {
        log_info("Verifying class %s with new format", _klass.thisClassName());
        var errors = new ArrayList<VerifyError>();
        for (VerificationWrapper.MethodWrapper m : _klass.methods()) {
            if (m.isNative() || m.isAbstract() || m.isBridge()) {
                continue;
            }
            verify_method(m, errors);
        }
        return errors;
    }

    void translate_signature(String method_sig, sig_as_verification_types sig_verif_types) {
        var sig_stream = new VerificationSignature(method_sig, true, this);
        VerificationType[] sig_type = new VerificationType[2];
        int sig_i = 0;
        ArrayList<VerificationType> verif_types = sig_verif_types.sig_verif_types();
        while (!sig_stream.atReturnType()) {
            int n = change_sig_to_verificationType(sig_stream, sig_type, 0);
            if (n > 2) verifyError("Unexpected signature type");
            for (int x = 0; x < n; x++) {
                verif_types.add(sig_type[x]);
            }
            sig_i += n;
            sig_stream.next();
        }
        sig_verif_types.set_num_args(sig_i);
        if (sig_stream.type() != BasicType.T_VOID) {
            int n = change_sig_to_verificationType(sig_stream, sig_type, 0);
            if (n > 2) verifyError("Unexpected signature return type");
            for (int y = 0; y < n; y++) {
                verif_types.add(sig_type[y]);
            }
        }
    }

    void create_method_sig_entry(sig_as_verification_types sig_verif_types, String method_sig) {
        translate_signature(method_sig, sig_verif_types);
    }

    void verify_method(VerificationWrapper.MethodWrapper m, List<VerifyError> errorsCollector) {
        try {
            verify_method(m, m.maxLocals(), m.maxStack(), m.stackMapTableRawData());
        } catch (VerifyError err) {
            errorsCollector.add(err);
        } catch (Error | Exception e) {
            e.printStackTrace();
            errorsCollector.add(new VerifyError(e.toString()));
        }
    }

    @SuppressWarnings("fallthrough")
    void verify_method(VerificationWrapper.MethodWrapper m, int max_locals, int max_stack, byte[] stackmap_data) {
        _method = m;
        log_info(_logger, "Verifying method %s%s", m.name(), m.descriptor());
        var cp = m.constantPool();
        if (!VerificationSignature.isValidMethodSignature(m.descriptor())) verifyError("Invalid method signature");
        VerificationFrame current_frame = new VerificationFrame(max_locals, max_stack, this);
        VerificationType return_type = current_frame.set_locals_from_arg(m, current_type());
        int stackmap_index = 0;
        int code_length = m.codeLength();
        var code = ByteBuffer.wrap(_method.codeArray(), 0, _method.codeLength());
        byte[] code_data = generate_code_data(code, code_length);
        int ex_minmax[] = new int[] {code_length, -1};
        verify_exception_handler_table(code_length, code_data, ex_minmax);
        verify_local_variable_table(code_length, code_data);

        VerificationTable stackmap_table = new VerificationTable(stackmap_data, current_frame, max_locals, max_stack, code_data, code_length, cp, this);

        var bcs = new RawBytecodeHelper(code);
        boolean no_control_flow = false;
        int opcode;
        while (!bcs.isLastBytecode()) {
            opcode = bcs.rawNext();
            bci = bcs.bci;
            current_frame.set_offset(bci);
            current_frame.set_mark();
            stackmap_index = verify_stackmap_table(stackmap_index, bci, current_frame, stackmap_table, no_control_flow);
            boolean this_uninit = false;
            boolean verified_exc_handlers = false;
            {
                int index;
                int target;
                VerificationType type, type2 = null;
                VerificationType atype;
                if (bcs.isWide) {
                    if (opcode != Classfile.IINC && opcode != Classfile.ILOAD
                        && opcode != Classfile.ALOAD && opcode != Classfile.LLOAD
                        && opcode != Classfile.ISTORE && opcode != Classfile.ASTORE
                        && opcode != Classfile.LSTORE && opcode != Classfile.FLOAD
                        && opcode != Classfile.DLOAD && opcode != Classfile.FSTORE
                        && opcode != Classfile.DSTORE) {
                        verifyError("Bad wide instruction");
                    }
                }
                if (VerificationBytecodes.is_store_into_local(opcode) && bci >= ex_minmax[0] && bci < ex_minmax[1]) {
                    verify_exception_handler_targets(bci, this_uninit, current_frame, stackmap_table);
                    verified_exc_handlers = true;
                }
                switch (opcode) {
                    case Classfile.NOP :
                        no_control_flow = false; break;
                    case Classfile.ACONST_NULL :
                        current_frame.push_stack(
                            VerificationType.null_type);
                        no_control_flow = false; break;
                    case Classfile.ICONST_M1 :
                    case Classfile.ICONST_0 :
                    case Classfile.ICONST_1 :
                    case Classfile.ICONST_2 :
                    case Classfile.ICONST_3 :
                    case Classfile.ICONST_4 :
                    case Classfile.ICONST_5 :
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.LCONST_0 :
                    case Classfile.LCONST_1 :
                        current_frame.push_stack_2(
                            VerificationType.long_type,
                            VerificationType.long2_type);
                        no_control_flow = false; break;
                    case Classfile.FCONST_0 :
                    case Classfile.FCONST_1 :
                    case Classfile.FCONST_2 :
                        current_frame.push_stack(
                            VerificationType.float_type);
                        no_control_flow = false; break;
                    case Classfile.DCONST_0 :
                    case Classfile.DCONST_1 :
                        current_frame.push_stack_2(
                            VerificationType.double_type,
                            VerificationType.double2_type);
                        no_control_flow = false; break;
                    case Classfile.SIPUSH :
                    case Classfile.BIPUSH :
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.LDC :
                        verify_ldc(
                            opcode, bcs.getIndexU1(), current_frame,
                            cp, bci);
                        no_control_flow = false; break;
                    case Classfile.LDC_W :
                    case Classfile.LDC2_W :
                        verify_ldc(
                            opcode, bcs.getIndexU2(), current_frame,
                            cp, bci);
                        no_control_flow = false; break;
                    case Classfile.ILOAD :
                        verify_iload(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.ILOAD_0 :
                    case Classfile.ILOAD_1 :
                    case Classfile.ILOAD_2 :
                    case Classfile.ILOAD_3 :
                        index = opcode - Classfile.ILOAD_0;
                        verify_iload(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.LLOAD :
                        verify_lload(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.LLOAD_0 :
                    case Classfile.LLOAD_1 :
                    case Classfile.LLOAD_2 :
                    case Classfile.LLOAD_3 :
                        index = opcode - Classfile.LLOAD_0;
                        verify_lload(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.FLOAD :
                        verify_fload(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.FLOAD_0 :
                    case Classfile.FLOAD_1 :
                    case Classfile.FLOAD_2 :
                    case Classfile.FLOAD_3 :
                        index = opcode - Classfile.FLOAD_0;
                        verify_fload(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.DLOAD :
                        verify_dload(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.DLOAD_0 :
                    case Classfile.DLOAD_1 :
                    case Classfile.DLOAD_2 :
                    case Classfile.DLOAD_3 :
                        index = opcode - Classfile.DLOAD_0;
                        verify_dload(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.ALOAD :
                        verify_aload(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.ALOAD_0 :
                    case Classfile.ALOAD_1 :
                    case Classfile.ALOAD_2 :
                    case Classfile.ALOAD_3 :
                        index = opcode - Classfile.ALOAD_0;
                        verify_aload(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.IALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_int_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.BALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_bool_array() && !atype.is_byte_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.CALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_char_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.SALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_short_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.LALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_long_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack_2(
                            VerificationType.long_type,
                            VerificationType.long2_type);
                        no_control_flow = false; break;
                    case Classfile.FALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_float_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(
                            VerificationType.float_type);
                        no_control_flow = false; break;
                    case Classfile.DALOAD :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_double_array()) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack_2(
                            VerificationType.double_type,
                            VerificationType.double2_type);
                        no_control_flow = false; break;
                    case Classfile.AALOAD : {
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_reference_array()) {
                            verifyError("Bad type");
                        }
                        if (atype.is_null()) {
                            current_frame.push_stack(
                                VerificationType.null_type);
                        } else {
                            VerificationType component =
                                atype.get_component(this);
                            current_frame.push_stack(component);
                        }
                        no_control_flow = false; break;
                    }
                    case Classfile.ISTORE :
                        verify_istore(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.ISTORE_0 :
                    case Classfile.ISTORE_1 :
                    case Classfile.ISTORE_2 :
                    case Classfile.ISTORE_3 :
                        index = opcode - Classfile.ISTORE_0;
                        verify_istore(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.LSTORE :
                        verify_lstore(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.LSTORE_0 :
                    case Classfile.LSTORE_1 :
                    case Classfile.LSTORE_2 :
                    case Classfile.LSTORE_3 :
                        index = opcode - Classfile.LSTORE_0;
                        verify_lstore(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.FSTORE :
                        verify_fstore(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.FSTORE_0 :
                    case Classfile.FSTORE_1 :
                    case Classfile.FSTORE_2 :
                    case Classfile.FSTORE_3 :
                        index = opcode - Classfile.FSTORE_0;
                        verify_fstore(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.DSTORE :
                        verify_dstore(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.DSTORE_0 :
                    case Classfile.DSTORE_1 :
                    case Classfile.DSTORE_2 :
                    case Classfile.DSTORE_3 :
                        index = opcode - Classfile.DSTORE_0;
                        verify_dstore(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.ASTORE :
                        verify_astore(bcs.getIndex(), current_frame);
                        no_control_flow = false; break;
                    case Classfile.ASTORE_0 :
                    case Classfile.ASTORE_1 :
                    case Classfile.ASTORE_2 :
                    case Classfile.ASTORE_3 :
                        index = opcode - Classfile.ASTORE_0;
                        verify_astore(index, current_frame);
                        no_control_flow = false; break;
                    case Classfile.IASTORE :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        type2 = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_int_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.BASTORE :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        type2 = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_bool_array() && !atype.is_byte_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.CASTORE :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_char_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.SASTORE :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_short_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.LASTORE :
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_long_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.FASTORE :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.pop_stack
                            (VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_float_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.DASTORE :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!atype.is_double_array()) {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.AASTORE :
                        type = current_frame.pop_stack(object_type());
                        type2 = current_frame.pop_stack(
                            VerificationType.integer_type);
                        atype = current_frame.pop_stack(
                            VerificationType.reference_check);
                        // more type-checking is done at runtime
                        if (!atype.is_reference_array()) {
                            verifyError("Bad type");
                        }
                        // 4938384: relaxed constraint in JVMS 3nd edition.
                        no_control_flow = false; break;
                    case Classfile.POP :
                        current_frame.pop_stack(
                            VerificationType.category1_check);
                        no_control_flow = false; break;
                    case Classfile.POP2 :
                        type = current_frame.pop_stack();
                        if (type.is_category1(this)) {
                            current_frame.pop_stack(
                                VerificationType.category1_check);
                        } else if (type.is_category2_2nd()) {
                            current_frame.pop_stack(
                                VerificationType.category2_check);
                        } else {
                            verifyError("Bad type");
                        }
                        no_control_flow = false; break;
                    case Classfile.DUP :
                        type = current_frame.pop_stack(
                            VerificationType.category1_check);
                        current_frame.push_stack(type);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    case Classfile.DUP_X1 :
                        type = current_frame.pop_stack(
                            VerificationType.category1_check);
                        type2 = current_frame.pop_stack(
                            VerificationType.category1_check);
                        current_frame.push_stack(type);
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    case Classfile.DUP_X2 :
                    {
                        VerificationType type3 = null;
                        type = current_frame.pop_stack(
                            VerificationType.category1_check);
                        type2 = current_frame.pop_stack();
                        if (type2.is_category1(this)) {
                            type3 = current_frame.pop_stack(
                                VerificationType.category1_check);
                        } else if (type2.is_category2_2nd()) {
                            type3 = current_frame.pop_stack(
                                VerificationType.category2_check);
                        } else {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(type);
                        current_frame.push_stack(type3);
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    }
                    case Classfile.DUP2 :
                        type = current_frame.pop_stack();
                        if (type.is_category1(this)) {
                            type2 = current_frame.pop_stack(
                                VerificationType.category1_check);
                        } else if (type.is_category2_2nd()) {
                            type2 = current_frame.pop_stack(
                                VerificationType.category2_check);
                        } else {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    case Classfile.DUP2_X1 :
                    {
                        VerificationType type3;
                        type = current_frame.pop_stack();
                        if (type.is_category1(this)) {
                            type2 = current_frame.pop_stack(
                                VerificationType.category1_check);
                        } else if (type.is_category2_2nd()) {
                            type2 = current_frame.pop_stack(
                                VerificationType.category2_check);
                        } else {
                            verifyError("Bad type");
                        }
                        type3 = current_frame.pop_stack(
                            VerificationType.category1_check);
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        current_frame.push_stack(type3);
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    }
                    case Classfile.DUP2_X2 :
                        VerificationType type3, type4 = null;
                        type = current_frame.pop_stack();
                        if (type.is_category1(this)) {
                            type2 = current_frame.pop_stack(
                                VerificationType.category1_check);
                        } else if (type.is_category2_2nd()) {
                            type2 = current_frame.pop_stack(
                                VerificationType.category2_check);
                        } else {
                            verifyError("Bad type");
                        }
                        type3 = current_frame.pop_stack();
                        if (type3.is_category1(this)) {
                            type4 = current_frame.pop_stack(
                                VerificationType.category1_check);
                        } else if (type3.is_category2_2nd()) {
                            type4 = current_frame.pop_stack(
                                VerificationType.category2_check);
                        } else {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        current_frame.push_stack(type4);
                        current_frame.push_stack(type3);
                        current_frame.push_stack(type2);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    case Classfile.SWAP :
                        type = current_frame.pop_stack(
                            VerificationType.category1_check);
                        type2 = current_frame.pop_stack(
                            VerificationType.category1_check);
                        current_frame.push_stack(type);
                        current_frame.push_stack(type2);
                        no_control_flow = false; break;
                    case Classfile.IADD :
                    case Classfile.ISUB :
                    case Classfile.IMUL :
                    case Classfile.IDIV :
                    case Classfile.IREM :
                    case Classfile.ISHL :
                    case Classfile.ISHR :
                    case Classfile.IUSHR :
                    case Classfile.IOR :
                    case Classfile.IXOR :
                    case Classfile.IAND :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        // fall through
                    case Classfile.INEG :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.LADD :
                    case Classfile.LSUB :
                    case Classfile.LMUL :
                    case Classfile.LDIV :
                    case Classfile.LREM :
                    case Classfile.LAND :
                    case Classfile.LOR :
                    case Classfile.LXOR :
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        // fall through
                    case Classfile.LNEG :
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.push_stack_2(
                            VerificationType.long_type,
                            VerificationType.long2_type);
                        no_control_flow = false; break;
                    case Classfile.LSHL :
                    case Classfile.LSHR :
                    case Classfile.LUSHR :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.push_stack_2(
                            VerificationType.long_type,
                            VerificationType.long2_type);
                        no_control_flow = false; break;
                    case Classfile.FADD :
                    case Classfile.FSUB :
                    case Classfile.FMUL :
                    case Classfile.FDIV :
                    case Classfile.FREM :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        // fall through
                    case Classfile.FNEG :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.push_stack(
                            VerificationType.float_type);
                        no_control_flow = false; break;
                    case Classfile.DADD :
                    case Classfile.DSUB :
                    case Classfile.DMUL :
                    case Classfile.DDIV :
                    case Classfile.DREM :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        // fall through
                    case Classfile.DNEG :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.push_stack_2(
                            VerificationType.double_type,
                            VerificationType.double2_type);
                        no_control_flow = false; break;
                                case Classfile.IINC :
                                    verify_iinc(bcs.getIndex(), current_frame);
                                    no_control_flow = false; break;
                                case Classfile.I2L :
                                    type = current_frame.pop_stack(
                                        VerificationType.integer_type);
                                    current_frame.push_stack_2(
                                        VerificationType.long_type,
                                        VerificationType.long2_type);
                                    no_control_flow = false; break;
                             case Classfile.L2I :
                                    current_frame.pop_stack_2(
                                        VerificationType.long2_type,
                                        VerificationType.long_type);
                                    current_frame.push_stack(
                                        VerificationType.integer_type);
                                    no_control_flow = false; break;
                                case Classfile.I2F :
                                    current_frame.pop_stack(
                                        VerificationType.integer_type);
                                    current_frame.push_stack(
                                        VerificationType.float_type);
                                    no_control_flow = false; break;
                    case Classfile.I2D :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.push_stack_2(
                            VerificationType.double_type,
                            VerificationType.double2_type);
                        no_control_flow = false; break;
                    case Classfile.L2F :
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.push_stack(
                            VerificationType.float_type);
                        no_control_flow = false; break;
                    case Classfile.L2D :
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.push_stack_2(
                            VerificationType.double_type,
                            VerificationType.double2_type);
                        no_control_flow = false; break;
                    case Classfile.F2I :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.F2L :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.push_stack_2(
                            VerificationType.long_type,
                            VerificationType.long2_type);
                        no_control_flow = false; break;
                    case Classfile.F2D :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.push_stack_2(
                            VerificationType.double_type,
                            VerificationType.double2_type);
                        no_control_flow = false; break;
                    case Classfile.D2I :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.D2L :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.push_stack_2(
                            VerificationType.long_type,
                            VerificationType.long2_type);
                        no_control_flow = false; break;
                    case Classfile.D2F :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.push_stack(
                            VerificationType.float_type);
                        no_control_flow = false; break;
                    case Classfile.I2B :
                    case Classfile.I2C :
                    case Classfile.I2S :
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.LCMP :
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.pop_stack_2(
                            VerificationType.long2_type,
                            VerificationType.long_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.FCMPL :
                    case Classfile.FCMPG :
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.pop_stack(
                            VerificationType.float_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.DCMPL :
                    case Classfile.DCMPG :
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.pop_stack_2(
                            VerificationType.double2_type,
                            VerificationType.double_type);
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.IF_ICMPEQ:
                    case Classfile.IF_ICMPNE:
                    case Classfile.IF_ICMPLT:
                    case Classfile.IF_ICMPGE:
                    case Classfile.IF_ICMPGT:
                    case Classfile.IF_ICMPLE:
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        // fall through
                    case Classfile.IFEQ:
                    case Classfile.IFNE:
                    case Classfile.IFLT:
                    case Classfile.IFGE:
                    case Classfile.IFGT:
                    case Classfile.IFLE:
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        target = bcs.dest();
                        stackmap_table.check_jump_target(
                            current_frame, target);
                        no_control_flow = false; break;
                    case Classfile.IF_ACMPEQ :
                    case Classfile.IF_ACMPNE :
                        current_frame.pop_stack(
                            VerificationType.reference_check);
                        // fall through
                    case Classfile.IFNULL :
                    case Classfile.IFNONNULL :
                        current_frame.pop_stack(
                            VerificationType.reference_check);
                        target = bcs.dest();
                        stackmap_table.check_jump_target
                            (current_frame, target);
                        no_control_flow = false; break;
                    case Classfile.GOTO :
                        target = bcs.dest();
                        stackmap_table.check_jump_target(
                            current_frame, target);
                        no_control_flow = true; break;
                    case Classfile.GOTO_W :
                        target = bcs.destW();
                        stackmap_table.check_jump_target(
                            current_frame, target);
                        no_control_flow = true; break;
                    case Classfile.TABLESWITCH :
                    case Classfile.LOOKUPSWITCH :
                        verify_switch(
                            bcs, code_length, code_data, current_frame,
                            stackmap_table);
                        no_control_flow = true; break;
                    case Classfile.IRETURN :
                        type = current_frame.pop_stack(
                            VerificationType.integer_type);
                        verify_return_value(return_type, type, bci,
                                                                current_frame);
                        no_control_flow = true; break;
                    case Classfile.LRETURN :
                        type2 = current_frame.pop_stack(
                            VerificationType.long2_type);
                        type = current_frame.pop_stack(
                            VerificationType.long_type);
                        verify_return_value(return_type, type, bci,
                                                                current_frame);
                        no_control_flow = true; break;
                    case Classfile.FRETURN :
                        type = current_frame.pop_stack(
                            VerificationType.float_type);
                        verify_return_value(return_type, type, bci,
                                                                current_frame);
                        no_control_flow = true; break;
                    case Classfile.DRETURN :
                        type2 = current_frame.pop_stack(
                            VerificationType.double2_type);
                        type = current_frame.pop_stack(
                            VerificationType.double_type);
                        verify_return_value(return_type, type, bci,
                                                                current_frame);
                        no_control_flow = true; break;
                    case Classfile.ARETURN :
                        type = current_frame.pop_stack(
                            VerificationType.reference_check);
                        verify_return_value(return_type, type, bci,
                                                                current_frame);
                        no_control_flow = true; break;
                    case Classfile.RETURN:
                        if (!return_type.is_bogus()) {
                            verifyError("Method expects a return value");
                        }
                        if (object_initializer_name.equals(_method.name()) &&
                                current_frame.flag_this_uninit()) {
                            verifyError("Constructor must call super() or this() before return");
                        }
                        no_control_flow = true; break;
                    case Classfile.GETSTATIC :
                    case Classfile.PUTSTATIC :
                        verify_field_instructions(bcs, current_frame, cp, true);
                        no_control_flow = false; break;
                    case Classfile.GETFIELD :
                    case Classfile.PUTFIELD :
                        verify_field_instructions(bcs, current_frame, cp, false);
                        no_control_flow = false; break;
                    case Classfile.INVOKEVIRTUAL :
                    case Classfile.INVOKESPECIAL :
                    case Classfile.INVOKESTATIC :
                        this_uninit = verify_invoke_instructions(bcs, code_length, current_frame, (bci >= ex_minmax[0] && bci < ex_minmax[1]), this_uninit, return_type, cp, stackmap_table);
                        no_control_flow = false; break;
                    case Classfile.INVOKEINTERFACE :
                    case Classfile.INVOKEDYNAMIC :
                        this_uninit = verify_invoke_instructions(bcs, code_length, current_frame, (bci >= ex_minmax[0] && bci < ex_minmax[1]), this_uninit, return_type, cp, stackmap_table);
                        no_control_flow = false; break;
                    case Classfile.NEW :
                    {
                        index = bcs.getIndexU2();
                        verify_cp_class_type(bci, index, cp);
                        VerificationType new_class_type =
                            cp_index_to_type(index, cp);
                        if (!new_class_type.is_object()) {
                            verifyError("Illegal new instruction");
                        }
                        type = VerificationType.uninitialized_type(bci);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    }
                    case Classfile.NEWARRAY :
                        type = get_newarray_type(bcs.getIndex(), bci);
                        current_frame.pop_stack(
                            VerificationType.integer_type);
                        current_frame.push_stack(type);
                        no_control_flow = false; break;
                    case Classfile.ANEWARRAY :
                        verify_anewarray(bci, bcs.getIndexU2(), cp, current_frame);
                        no_control_flow = false; break;
                    case Classfile.ARRAYLENGTH :
                        type = current_frame.pop_stack(
                            VerificationType.reference_check);
                        if (!(type.is_null() || type.is_array())) {
                            verifyError("Bad type");
                        }
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    case Classfile.CHECKCAST :
                    {
                        index = bcs.getIndexU2();
                        verify_cp_class_type(bci, index, cp);
                        current_frame.pop_stack(object_type());
                        VerificationType klass_type = cp_index_to_type(
                            index, cp);
                        current_frame.push_stack(klass_type);
                        no_control_flow = false; break;
                    }
                    case Classfile.INSTANCEOF : {
                        index = bcs.getIndexU2();
                        verify_cp_class_type(bci, index, cp);
                        current_frame.pop_stack(object_type());
                        current_frame.push_stack(
                            VerificationType.integer_type);
                        no_control_flow = false; break;
                    }
                    case Classfile.MONITORENTER :
                    case Classfile.MONITOREXIT :
                        current_frame.pop_stack(
                            VerificationType.reference_check);
                        no_control_flow = false; break;
                    case Classfile.MULTIANEWARRAY :
                    {
                        index = bcs.getIndexU2();
                        int dim = _method.codeArray()[bcs.bci+3] & 0xff;
                        verify_cp_class_type(bci, index, cp);
                        VerificationType new_array_type =
                            cp_index_to_type(index, cp);
                        if (!new_array_type.is_array()) {
                            verifyError("Illegal constant pool index in multianewarray instruction");
                        }
                        if (dim < 1 || new_array_type.dimensions(this) < dim) {
                            verifyError(String.format("Illegal dimension in multianewarray instruction: %d", dim));
                        }
                        for (int i = 0; i < dim; i++) {
                            current_frame.pop_stack(
                                VerificationType.integer_type);
                        }
                        current_frame.push_stack(new_array_type);
                        no_control_flow = false; break;
                    }
                    case Classfile.ATHROW :
                        type = VerificationType.reference_type(java_lang_Throwable);
                        current_frame.pop_stack(type);
                        no_control_flow = true; break;
                    default:
                        verifyError(String.format("Bad instruction: %02x", opcode));
                }
            }
            if (verified_exc_handlers && this_uninit) verifyError("Exception handler targets got verified before this_uninit got set");
            if (!verified_exc_handlers && bci >= ex_minmax[0] && bci < ex_minmax[1]) {
                verify_exception_handler_targets(bci, this_uninit, current_frame, stackmap_table);
            }
        }
        if (!no_control_flow) {
            verifyError("Control flow falls through code end");
        }
    }

    private byte[] generate_code_data(ByteBuffer code, int code_length) {
        byte code_data[] = new byte[code_length];
        var bcs = new RawBytecodeHelper(code);
        while (!bcs.isLastBytecode()) {
            if (bcs.rawNext() != ILLEGAL) {
                int bci = bcs.bci;
                if (bcs.rawCode == Classfile.NEW) {
                    code_data[bci] = NEW_OFFSET;
                } else {
                    code_data[bci] = BYTECODE_OFFSET;
                }
            } else {
                verifyError("Bad instruction");
            }
        }
        return code_data;
    }

    void verify_exception_handler_table(int code_length, byte[] code_data, int[] minmax) {
        var cp = _method.constantPool();
        for (var exhandler : _method.exceptionTable()) {
            int start_pc = exhandler[0];
            int end_pc = exhandler[1];
            int handler_pc = exhandler[2];
            if (start_pc >= code_length || code_data[start_pc] == 0) {
                classError(String.format("Illegal exception table start_pc %d", start_pc));
            }
            if (end_pc != code_length) {
                if (end_pc > code_length || code_data[end_pc] == 0) {
                    classError(String.format("Illegal exception table end_pc %d", end_pc));
                }
            }
            if (handler_pc >= code_length || code_data[handler_pc] == 0) {
                classError(String.format("Illegal exception table handler_pc %d", handler_pc));
            }
            int catch_type_index = exhandler[3];
            if (catch_type_index != 0) {
                VerificationType catch_type = cp_index_to_type(catch_type_index, cp);
                VerificationType throwable = VerificationType.reference_type(java_lang_Throwable);
                boolean is_subclass = throwable.is_assignable_from(catch_type, this);
                if (!is_subclass) {
                    verifyError(String.format("Catch type is not a subclass of Throwable in exception handler %d", handler_pc));
                }
            }
            if (start_pc < minmax[0]) minmax[0] = start_pc;
            if (end_pc > minmax[1]) minmax[1] = end_pc;
        }
    }

    void verify_local_variable_table(int code_length, byte[] code_data) {
        for (var lvte : _method.localVariableTable()) {
            int start_bci = lvte.startPc();
            int length = lvte.length();
            if (start_bci >= code_length || code_data[start_bci] == 0) {
                classError(String.format("Illegal local variable table start_pc %d", start_bci));
            }
            int end_bci = start_bci + length;
            if (end_bci != code_length) {
                if (end_bci >= code_length || code_data[end_bci] == 0) {
                    classError(String.format("Illegal local variable table length %d", length));
                }
            }
        }
    }

    int verify_stackmap_table(int stackmap_index, int bci, VerificationFrame current_frame, VerificationTable stackmap_table, boolean no_control_flow) {
        if (stackmap_index < stackmap_table.get_frame_count()) {
            int this_offset = stackmap_table.get_offset(stackmap_index);
            if (no_control_flow && this_offset > bci) {
                verifyError("Expecting a stack map frame");
            }
            if (this_offset == bci) {
                boolean matches = stackmap_table.match_stackmap(current_frame, this_offset, stackmap_index, !no_control_flow, true);
                if (!matches) {
                    verifyError("Instruction type does not match stack map");
                }
                stackmap_index++;
            } else if (this_offset < bci) {
                classError(String.format("Bad stack map offset %d", this_offset));
            }
        } else if (no_control_flow) {
            verifyError("Expecting a stack map frame");
        }
        return stackmap_index;
    }

    void verify_exception_handler_targets(int bci, boolean this_uninit, VerificationFrame current_frame, VerificationTable stackmap_table) {
        var cp = _method.constantPool();
        for(var exhandler : _method.exceptionTable()) {
            int start_pc = exhandler[0];
            int end_pc = exhandler[1];
            int handler_pc = exhandler[2];
            int catch_type_index = exhandler[3];
            if(bci >= start_pc && bci < end_pc) {
                int flags = current_frame.flags();
                if (this_uninit) {    flags |= FLAG_THIS_UNINIT; }
                VerificationFrame new_frame = current_frame.frame_in_exception_handler(flags);
                if (catch_type_index != 0) {
                    VerificationType catch_type = cp_index_to_type(catch_type_index, cp);
                    new_frame.push_stack(catch_type);
                } else {
                    VerificationType throwable = VerificationType.reference_type(java_lang_Throwable);
                    new_frame.push_stack(throwable);
                }
                boolean matches = stackmap_table.match_stackmap(new_frame, handler_pc, true, false);
                if (!matches) {
                    verifyError(String.format("Stack map does not match the one at exception handler %d", handler_pc));
                }
            }
        }
    }

    void verify_cp_index(int bci, ConstantPoolWrapper cp, int index) {
        int nconstants = cp.entryCount();
        if ((index <= 0) || (index >= nconstants)) {
            verifyError(String.format("Illegal constant pool index %d", index));
        }
    }

    void verify_cp_type(int bci, int index, ConstantPoolWrapper cp, int types) {
        verify_cp_index(bci, cp, index);
        int tag = cp.tagAt(index);
        if ((types & (1 << tag))== 0) {
            verifyError(String.format("Illegal type at constant pool entry %d", index));
        }
    }

    void verify_cp_class_type(int bci, int index, ConstantPoolWrapper cp) {
        verify_cp_index(bci, cp, index);
        int tag = cp.tagAt(index);
        if (tag != JVM_CONSTANT_Class) {
            verifyError(String.format("Illegal type at constant pool entry %d", index));
        }
    }

    void verify_ldc(int opcode, int index, VerificationFrame current_frame, ConstantPoolWrapper cp, int bci) {
        verify_cp_index(bci, cp, index);
        int tag = cp.tagAt(index);
        int types = 0;
        if (opcode == Classfile.LDC || opcode == Classfile.LDC_W) {
            types = (1 << JVM_CONSTANT_Integer) | (1 << JVM_CONSTANT_Float)
                    | (1 << JVM_CONSTANT_String) | (1 << JVM_CONSTANT_Class)
                    | (1 << JVM_CONSTANT_MethodHandle) | (1 << JVM_CONSTANT_MethodType)
                    | (1 << JVM_CONSTANT_Dynamic);
            verify_cp_type(bci, index, cp, types);
        } else {
            if (opcode != Classfile.LDC2_W) verifyError("must be ldc2_w");
            types = (1 << JVM_CONSTANT_Double) | (1 << JVM_CONSTANT_Long) | (1 << JVM_CONSTANT_Dynamic);
            verify_cp_type(bci, index, cp, types);
        }
        switch (tag) {
            case JVM_CONSTANT_Utf8 -> current_frame.push_stack(object_type());
            case JVM_CONSTANT_String -> current_frame.push_stack(VerificationType.reference_type(java_lang_String));
            case JVM_CONSTANT_Class -> current_frame.push_stack(VerificationType.reference_type(java_lang_Class));
            case JVM_CONSTANT_Integer -> current_frame.push_stack(VerificationType.integer_type);
            case JVM_CONSTANT_Float -> current_frame.push_stack(VerificationType.float_type);
            case JVM_CONSTANT_Double -> current_frame.push_stack_2(VerificationType.double_type, VerificationType.double2_type);
            case JVM_CONSTANT_Long -> current_frame.push_stack_2(VerificationType.long_type, VerificationType.long2_type);
            case JVM_CONSTANT_MethodHandle -> current_frame.push_stack(VerificationType.reference_type(java_lang_invoke_MethodHandle));
            case JVM_CONSTANT_MethodType -> current_frame.push_stack(VerificationType.reference_type(java_lang_invoke_MethodType));
            case JVM_CONSTANT_Dynamic -> {
                String constant_type = cp.dynamicConstantSignatureAt(index);
                if (!VerificationSignature.isValidTypeSignature(constant_type)) verifyError("Invalid type for dynamic constant");
                VerificationType[] v_constant_type = new VerificationType[2];
                var sig_stream = new VerificationSignature(constant_type, false, this);
                int n = change_sig_to_verificationType(sig_stream, v_constant_type, 0);
                int opcode_n = (opcode == Classfile.LDC2_W ? 2 : 1);
                if (n != opcode_n) {
                    types &= ~(1 << JVM_CONSTANT_Dynamic);
                    verify_cp_type(bci, index, cp, types);
                }
                for (int i = 0; i < n; i++) {
                    current_frame.push_stack(v_constant_type[i]);
                }
            }
            default -> verifyError("Invalid index in ldc");
        }
    }

    void verify_switch(RawBytecodeHelper bcs, int code_length, byte[] code_data, VerificationFrame current_frame, VerificationTable stackmap_table) {
        int bci = bcs.bci;
        int aligned_bci = VerificationBytecodes.align(bci + 1);
        // 4639449 & 4647081: padding bytes must be 0
        if (_klass.majorVersion() < NONZERO_PADDING_BYTES_IN_SWITCH_MAJOR_VERSION) {
            int padding_offset = 1;
            while ((bci + padding_offset) < aligned_bci) {
                if (_method.codeArray()[bci + padding_offset] != 0) {
                    verifyError("Nonzero padding byte in lookupswitch or tableswitch");
                }
                padding_offset++;
            }
        }
        int default_ofset = bcs.getInt(aligned_bci);
        int keys, delta;
        current_frame.pop_stack(VerificationType.integer_type);
        if (bcs.rawCode == Classfile.TABLESWITCH) {
            int low = bcs.getInt(aligned_bci + 4);
            int high = bcs.getInt(aligned_bci + 2*4);
            if (low > high) {
                verifyError("low must be less than or equal to high in tableswitch");
            }
            keys = high - low + 1;
            if (keys < 0) {
                verifyError("too many keys in tableswitch");
            }
            delta = 1;
        } else {
            // Make sure that the lookupswitch items are sorted
            keys = bcs.getInt(aligned_bci + 4);
            if (keys < 0) {
                verifyError("number of keys in lookupswitch less than 0");
            }
            delta = 2;
            for (int i = 0; i < (keys - 1); i++) {
                int this_key = bcs.getInt(aligned_bci + (2+2*i)*4);
                int next_key = bcs.getInt(aligned_bci + (2+2*i+2)*4);
                if (this_key >= next_key) {
                    verifyError("Bad lookupswitch instruction");
                }
            }
        }
        int target = bci + default_ofset;
        stackmap_table.check_jump_target(current_frame, target);
        for (int i = 0; i < keys; i++) {
            aligned_bci = VerificationBytecodes.align(bcs.bci + 1);
            target = bci + bcs.getInt(aligned_bci + (3+i*delta)*4);
            stackmap_table.check_jump_target(current_frame, target);
        }
    }

    void verify_field_instructions(RawBytecodeHelper bcs, VerificationFrame current_frame, ConstantPoolWrapper cp, boolean allow_arrays) {
        int index = bcs.getIndexU2();
        verify_cp_type(bcs.bci, index, cp, 1 << JVM_CONSTANT_Fieldref);
        String field_name = cp.refNameAt(index);
        String field_sig = cp.refSignatureAt(index);
        if (!VerificationSignature.isValidTypeSignature(field_sig)) verifyError("Invalid field signature");
        VerificationType ref_class_type = cp_ref_index_to_type(index, cp);
        if (!ref_class_type.is_object() &&
            (!allow_arrays || !ref_class_type.is_array())) {
            verifyError(String.format("Expecting reference to class in class %s at constant pool index %d", _klass.thisClassName(), index));
        }
        VerificationType target_class_type = ref_class_type;
        VerificationType[] field_type = new VerificationType[2];
        var sig_stream = new VerificationSignature(field_sig, false, this);
        VerificationType stack_object_type = null;
        int n = change_sig_to_verificationType(sig_stream, field_type, 0);
        boolean is_assignable;
        switch (bcs.rawCode) {
            case Classfile.GETSTATIC ->  {
                for (int i = 0; i < n; i++) {
                    current_frame.push_stack(field_type[i]);
                }
            }
            case Classfile.PUTSTATIC ->  {
                for (int i = n - 1; i >= 0; i--) {
                    current_frame.pop_stack(field_type[i]);
                }
            }
            case Classfile.GETFIELD ->  {
                stack_object_type = current_frame.pop_stack(
                    target_class_type);
                for (int i = 0; i < n; i++) {
                    current_frame.push_stack(field_type[i]);
                }
            }
            case Classfile.PUTFIELD ->  {
                for (int i = n - 1; i >= 0; i--) {
                    current_frame.pop_stack(field_type[i]);
                }
                stack_object_type = current_frame.pop_stack();
                if (stack_object_type.is_uninitialized_this(this) &&
                        target_class_type.equals(current_type()) &&
                        _klass.findField(field_name, field_sig)) {
                    stack_object_type = current_type();
                }
                is_assignable = target_class_type.is_assignable_from(stack_object_type, this);
                if (!is_assignable) {
                    verifyError("Bad type on operand stack in putfield");
                }
            }
            default -> verifyError("Should not reach here");
        }
    }

    // Return TRUE if all code paths starting with start_bc_offset end in
    // bytecode athrow or loop.
    boolean ends_in_athrow(int start_bc_offset) {
        log_info("unimplemented VerifierImpl.ends_in_athrow");
        return true;
    }

    boolean verify_invoke_init(RawBytecodeHelper bcs, int ref_class_index, VerificationType ref_class_type,
            VerificationFrame current_frame, int code_length, boolean in_try_block,
            boolean this_uninit, ConstantPoolWrapper cp, VerificationTable stackmap_table) {
        int bci = bcs.bci;
        VerificationType type = current_frame.pop_stack(VerificationType.reference_check);
        if (type.is_uninitialized_this(this)) {
            String superk_name = current_class().superclassName();
            if (!current_class().thisClassName().equals(ref_class_type.name()) &&
                    !superk_name.equals(ref_class_type.name())) {
                verifyError("Bad <init> method call");
            }
            if (in_try_block) {
                for(var exhandler : _method.exceptionTable()) {
                    int start_pc = exhandler[0];
                    int end_pc = exhandler[1];

                    if (bci >= start_pc && bci < end_pc) {
                        if (!ends_in_athrow(exhandler[2])) {
                            verifyError("Bad <init> method call from after the start of a try block");
                        }
                    }
                }
                verify_exception_handler_targets(bci, true, current_frame, stackmap_table);
            }
            current_frame.initialize_object(type, current_type());
            this_uninit = true;
        } else if (type.is_uninitialized()) {
            int new_offset = type.bci(this);
            if (new_offset > (code_length - 3) || (_method.codeArray()[new_offset] & 0xff) != Classfile.NEW) {
                verifyError("Expecting new instruction");
            }
            int new_class_index = bcs.getIndexU2Raw(new_offset + 1);
            verify_cp_class_type(bci, new_class_index, cp);
            VerificationType new_class_type = cp_index_to_type(
                new_class_index, cp);
            if (!new_class_type.equals(ref_class_type)) {
                verifyError("Call to wrong <init> method");
            }
            if (in_try_block) {
                verify_exception_handler_targets(bci, this_uninit, current_frame,
                                                                                 stackmap_table);
            }
            current_frame.initialize_object(type, new_class_type);
        } else {
            verifyError("Bad operand type when invoking <init>");
        }
        return this_uninit;
    }

    static boolean is_same_or_direct_interface(VerificationWrapper klass, VerificationType klass_type, VerificationType ref_class_type) {
        if (ref_class_type.equals(klass_type)) return true;
        for (String k_name : klass.interfaceNames()) {
            if (ref_class_type.equals(VerificationType.reference_type(k_name))) {
                return true;
            }
        }
        return false;
    }

    boolean verify_invoke_instructions(RawBytecodeHelper bcs, int code_length, VerificationFrame current_frame, boolean in_try_block, boolean this_uninit, VerificationType return_type, ConstantPoolWrapper cp, VerificationTable stackmap_table) {
        // Make sure the constant pool item is the right type
        int index = bcs.getIndexU2();
        int opcode = bcs.rawCode;
        int types = 0;
        switch (opcode) {
            case Classfile.INVOKEINTERFACE:
                types = 1 << JVM_CONSTANT_InterfaceMethodref;
                break;
            case Classfile.INVOKEDYNAMIC:
                types = 1 << JVM_CONSTANT_InvokeDynamic;
                break;
            case Classfile.INVOKESPECIAL:
            case Classfile.INVOKESTATIC:
                types = (_klass.majorVersion() < STATIC_METHOD_IN_INTERFACE_MAJOR_VERSION) ?
                    (1 << JVM_CONSTANT_Methodref) :
                    ((1 << JVM_CONSTANT_InterfaceMethodref) | (1 << JVM_CONSTANT_Methodref));
                break;
            default:
                types = 1 << JVM_CONSTANT_Methodref;
        }
        verify_cp_type(bcs.bci, index, cp, types);
        String method_name = cp.refNameAt(index);
        String method_sig = cp.refSignatureAt(index);
        if (!VerificationSignature.isValidMethodSignature(method_sig)) verifyError("Invalid method signature");
        VerificationType ref_class_type = null;
        if (opcode == Classfile.INVOKEDYNAMIC) {
            if (_klass.majorVersion() < INVOKEDYNAMIC_MAJOR_VERSION) {
                classError(String.format("invokedynamic instructions not supported by this class file version (%d), class %s", _klass.majorVersion(), _klass.thisClassName()));
            }
        } else {
            ref_class_type = cp_ref_index_to_type(index, cp);
        }
        String sig = cp.refSignatureAt(index);
        sig_as_verification_types mth_sig_verif_types;
        ArrayList<VerificationType> verif_types = new ArrayList<>(10);
        mth_sig_verif_types = new sig_as_verification_types(verif_types);
        create_method_sig_entry(mth_sig_verif_types, sig);
        int nargs = mth_sig_verif_types.num_args();
        int bci = bcs.bci;
        if (opcode == Classfile.INVOKEINTERFACE) {
            if ((_method.codeArray()[bci+3] & 0xff) != (nargs+1)) {
                verifyError("Inconsistent args count operand in invokeinterface");
            }
            if ((_method.codeArray()[bci+4] & 0xff) != 0) {
                verifyError("Fourth operand byte of invokeinterface must be zero");
            }
        }
        if (opcode == Classfile.INVOKEDYNAMIC) {
            if ((_method.codeArray()[bci+3] & 0xff) != 0 || (_method.codeArray()[bci+4] & 0xff) != 0) {
                verifyError("Third and fourth operand bytes of invokedynamic must be zero");
            }
        }
        if (method_name.charAt(0) == JVM_SIGNATURE_SPECIAL) {
            if (opcode != Classfile.INVOKESPECIAL ||
                !object_initializer_name.equals(method_name)) {
                verifyError("Illegal call to internal method");
            }
        } else if (opcode == Classfile.INVOKESPECIAL
                             && !is_same_or_direct_interface(current_class(), current_type(), ref_class_type)
                             && !ref_class_type.equals(VerificationType.reference_type(
                                        current_class().superclassName()))) {
            boolean have_imr_indirect = cp.tagAt(index) == JVM_CONSTANT_InterfaceMethodref;
            boolean subtype = ref_class_type.is_assignable_from(current_type(), this);
            if (!subtype) {
                verifyError("Bad invokespecial instruction: current class isn't assignable to reference class.");
            } else if (have_imr_indirect) {
                verifyError("Bad invokespecial instruction: interface method reference is in an indirect superinterface.");
            }

        }
        ArrayList<VerificationType> sig_verif_types = mth_sig_verif_types.sig_verif_types();
        if (sig_verif_types == null) verifyError("Missing signature's array of verification types");
        for (int i = nargs - 1; i >= 0; i--) { // Run backwards
            current_frame.pop_stack(sig_verif_types.get(i));
        }
        if (opcode != Classfile.INVOKESTATIC &&
            opcode != Classfile.INVOKEDYNAMIC) {
            if (object_initializer_name.equals(method_name)) {    // <init> method
                this_uninit = verify_invoke_init(bcs, index, ref_class_type, current_frame,
                    code_length, in_try_block, this_uninit, cp, stackmap_table);
            } else {
                switch (opcode) {
                    case Classfile.INVOKESPECIAL ->
                        current_frame.pop_stack(current_type());
                    case Classfile.INVOKEVIRTUAL -> {
                        VerificationType stack_object_type =
                                current_frame.pop_stack(ref_class_type);
                        if (current_type() != stack_object_type) {
                            cp.classNameAt(cp.refClassIndexAt(index));
                        }
                    }
                    default -> {
                        if (opcode != Classfile.INVOKEINTERFACE)
                            verifyError("Unexpected opcode encountered");
                        current_frame.pop_stack(ref_class_type);
                    }
                }
            }
        }
        int sig_verif_types_len = sig_verif_types.size();
        if (sig_verif_types_len > nargs) {    // There's a return type
            if (object_initializer_name.equals(method_name)) {
                verifyError("Return type must be void in <init> method");
            }

            if (sig_verif_types_len > nargs + 2) verifyError("Signature verification types array return type is bogus");
            for (int i = nargs; i < sig_verif_types_len; i++) {
                if (!(i == nargs || sig_verif_types.get(i).is_long2() || sig_verif_types.get(i).is_double2())) verifyError("Unexpected return verificationType");
                current_frame.push_stack(sig_verif_types.get(i));
            }
        }
        return this_uninit;
    }

    VerificationType get_newarray_type(int index, int bci) {
        String[] from_bt = new String[] {
            null, null, null, null, "[Z", "[C", "[F", "[D", "[B", "[S", "[I", "[J",
        };
        if (index < T_BOOLEAN.type || index > T_LONG.type) {
            verifyError("Illegal newarray instruction");
        }
        String sig = from_bt[index];
        return VerificationType.reference_type(sig);
    }

    void verify_anewarray(int bci, int index, ConstantPoolWrapper cp, VerificationFrame current_frame) {
        verify_cp_class_type(bci, index, cp);
        current_frame.pop_stack(VerificationType.integer_type);
        VerificationType component_type = cp_index_to_type(index, cp);
        int length;
        String arr_sig_str;
        if (component_type.is_array()) {         // it's an array
            String component_name = component_type.name();
            length = component_name.length();
            if (length > MAX_ARRAY_DIMENSIONS &&
                    component_name.charAt(MAX_ARRAY_DIMENSIONS - 1) == JVM_SIGNATURE_ARRAY) {
                verifyError("Illegal anewarray instruction, array has more than 255 dimensions");
            }
            length++;
            arr_sig_str = String.format("%c%s", JVM_SIGNATURE_ARRAY, component_name);
            if (arr_sig_str.length() != length) verifyError("Unexpected number of characters in string");
        } else {                 // it's an object or interface
            String component_name = component_type.name();
            length = component_name.length() + 3;
            arr_sig_str = String.format("%c%c%s;", JVM_SIGNATURE_ARRAY, JVM_SIGNATURE_CLASS, component_name);
            if (arr_sig_str.length() != length) verifyError("Unexpected number of characters in string");
        }
        VerificationType new_array_type = VerificationType.reference_type(arr_sig_str);
        current_frame.push_stack(new_array_type);
    }

    void verify_iload(int index, VerificationFrame current_frame) {
        current_frame.get_local(
            index, VerificationType.integer_type);
        current_frame.push_stack(
            VerificationType.integer_type);
    }

    void verify_lload(int index, VerificationFrame current_frame) {
        current_frame.get_local_2(
            index, VerificationType.long_type,
            VerificationType.long2_type);
        current_frame.push_stack_2(
            VerificationType.long_type,
            VerificationType.long2_type);
    }

    void verify_fload(int index, VerificationFrame current_frame) {
        current_frame.get_local(
            index, VerificationType.float_type);
        current_frame.push_stack(
            VerificationType.float_type);
    }

    void verify_dload(int index, VerificationFrame current_frame) {
        current_frame.get_local_2(
            index, VerificationType.double_type,
            VerificationType.double2_type);
        current_frame.push_stack_2(
            VerificationType.double_type,
            VerificationType.double2_type);
    }

    void verify_aload(int index, VerificationFrame current_frame) {
        VerificationType type = current_frame.get_local(
            index, VerificationType.reference_check);
        current_frame.push_stack(type);
    }

    void verify_istore(int index, VerificationFrame current_frame) {
        current_frame.pop_stack(
            VerificationType.integer_type);
        current_frame.set_local(
            index, VerificationType.integer_type);
    }

    void verify_lstore(int index, VerificationFrame current_frame) {
        current_frame.pop_stack_2(
            VerificationType.long2_type,
            VerificationType.long_type);
        current_frame.set_local_2(
            index, VerificationType.long_type,
            VerificationType.long2_type);
    }

    void verify_fstore(int index, VerificationFrame current_frame) {
        current_frame.pop_stack(
            VerificationType.float_type);
        current_frame.set_local(
            index, VerificationType.float_type);
    }

    void verify_dstore(int index, VerificationFrame current_frame) {
        current_frame.pop_stack_2(
            VerificationType.double2_type,
            VerificationType.double_type);
        current_frame.set_local_2(
            index, VerificationType.double_type,
            VerificationType.double2_type);
    }

    void verify_astore(int index, VerificationFrame current_frame) {
        VerificationType type = current_frame.pop_stack(
            VerificationType.reference_check);
        current_frame.set_local(index, type);
    }

    void verify_iinc(int index, VerificationFrame current_frame) {
        VerificationType type = current_frame.get_local(
            index, VerificationType.integer_type);
        current_frame.set_local(index, type);
    }

    void verify_return_value(VerificationType return_type, VerificationType type, int bci, VerificationFrame current_frame) {
        if (return_type.is_bogus()) {
            verifyError("Method expects a return value");
        }
        boolean match = return_type.is_assignable_from(type, this);
        if (!match) {
            verifyError("Bad return type");
        }
    }

    private void dumpMethod() {
        if (_logger != null) ClassPrinter.toTree(_method.m, ClassPrinter.Verbosity.CRITICAL_ATTRIBUTES).toYaml(_logger);
    }

    void verifyError(String msg) {
        dumpMethod();
        throw new VerifyError(String.format("%s at %s.%s%s @%d %s", msg, _klass.thisClassName(), _method.name(), _method.descriptor(), bci, errorContext));
    }

    void verifyError(String msg, VerificationFrame from, VerificationFrame target) {
        dumpMethod();
        throw new VerifyError(String.format("%s at %s.%s%s @%d %s%n  while assigning %s%n  to %s", msg, _klass.thisClassName(), _method.name(), _method.descriptor(), bci, errorContext, from, target));
    }

    void classError(String msg) {
        dumpMethod();
        throw new ClassFormatError(String.format("%s at %s.%s%s", msg, _klass.thisClassName(), _method.name(), _method.descriptor()));
    }
}
