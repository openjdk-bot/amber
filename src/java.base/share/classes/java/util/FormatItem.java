/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

import java.io.IOException;
import java.lang.invoke.*;
import java.lang.invoke.StringConcatFactory.StringConcatItem;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.util.Formatter.FormatSpecifier;

import static java.lang.invoke.MethodType.methodType;

/**
 * A specialized objects used by FormatBuilder that knows how to insert
 * themselves into a concatenation performed by StringConcatFactory.
 */
class FormatItem {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private static boolean isLATIN1(long lengthCoder) {
        return lengthCoder <= Integer.MAX_VALUE;
    }

    private static final MethodHandle STRING_PREPEND = JLA.stringConcatHelper("prepend",
            MethodType.methodType(long.class, long.class, byte[].class,
                    String.class, String.class));

    private static final MethodHandle MIX_CHAR = JLA.stringConcatHelper("mix",
            MethodType.methodType(long.class, long.class, char.class));

    private static final MethodHandle GETCHAR_LATIN1_MH =
            JLA.stringConcatHelper("getCharLatin1",
                    MethodType.methodType(char.class, byte[].class, int.class));

    private static final MethodHandle GETCHAR_UTF16_MH =
            JLA.stringConcatHelper("getCharUTF16",
                    MethodType.methodType(char.class, byte[].class, int.class));

    private static final MethodHandle PUTCHAR_LATIN1_MH =
            JLA.stringConcatHelper("putCharLatin1",
                    MethodType.methodType(void.class, byte[].class, int.class, int.class));

    private static final MethodHandle PUTCHAR_UTF16_MH =
            JLA.stringConcatHelper("putCharUTF16",
                    MethodType.methodType(void.class, byte[].class, int.class, int.class));

    private static MethodHandle selectGetChar(long indexCoder) {
        return isLATIN1(indexCoder) ? GETCHAR_LATIN1_MH : GETCHAR_UTF16_MH;
    }

    private static MethodHandle selectPutChar(long indexCoder) {
        return isLATIN1(indexCoder) ? PUTCHAR_LATIN1_MH : PUTCHAR_UTF16_MH;
    }

    private static final MethodHandle PUT_CHAR_DIGIT = selectPutChar(charCoder('0'));

    private FormatItem() {
        throw new AssertionError("private constructor");
    }

    private static long stringMix(long lengthCoder, String value) {
        return JLA.stringConcatMix(lengthCoder, value);
    }

    private static long stringPrepend(long lengthCoder, byte[] buffer, String value) {
        try {
            return (long)STRING_PREPEND.invokeExact(lengthCoder, buffer,
                    value, (String)null);
        } catch (Throwable ex) {
            throw new AssertionError("string prepend failed", ex);
        }
    }

    private static long charCoder(char ch) {
        try {
            return (long)MIX_CHAR.invokeExact(0L, ch) - 1;
        } catch (Throwable ex) {
            throw new AssertionError("char mix failed", ex);
        }
    }

    /**
     * Digits provides a fast methodology for converting integers and longs to
     * ASCII strings.
     */
    interface Digits {
        /**
         * Insert digits for long value in buffer from high index to low index.
         *
         * @param value      value to convert
         * @param buffer     byte buffer to copy into
         * @param index      insert point + 1
         * @param putCharMH  method to put character
         *
         * @return the last index used
         *
         * @throws Throwable if putCharMH fails (unusual).
         */
        int digits(long value, byte[] buffer, int index,
                   MethodHandle putCharMH) throws Throwable;

        /**
         * Calculate the number of digits required to represent the long.
         *
         * @param value value to convert
         *
         * @return number of digits
         */
        int size(long value);
    }

    /**
     * Digits class for decimal digits.
     */
    static final class DecimalDigits implements Digits {
        private static final short[] DIGITS;

        /**
         * Singleton instance of DecimalDigits.
         */
        static final Digits INSTANCE = new DecimalDigits();

        static {
            short[] digits = new short[10 * 10];

            for (int i = 0; i < 10; i++) {
                short hi = (short) ((i + '0') << 8);

                for (int j = 0; j < 10; j++) {
                    short lo = (short) (j + '0');
                    digits[i * 10 + j] = (short) (hi | lo);
                }
            }

            DIGITS = digits;
        }

        /**
         * Constructor.
         */
        private DecimalDigits() {
        }

        @Override
        public int digits(long value, byte[] buffer, int index,
                          MethodHandle putCharMH) throws Throwable {
            boolean negative = value < 0;
            if (!negative) {
                value = -value;
            }

            long q;
            int r;
            while (value <= Integer.MIN_VALUE) {
                q = value / 100;
                r = (int)((q * 100) - value);
                value = q;
                int digits = DIGITS[r];

                putCharMH.invokeExact(buffer, --index, digits & 0xFF);
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            int iq, ivalue = (int)value;
            while (ivalue <= -100) {
                iq = ivalue / 100;
                r = (iq * 100) - ivalue;
                ivalue = iq;
                int digits = DIGITS[r];
                putCharMH.invokeExact(buffer, --index, digits & 0xFF);
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            if (ivalue < 0) {
                ivalue = -ivalue;
            }

            int digits = DIGITS[ivalue];
            putCharMH.invokeExact(buffer, --index, digits & 0xFF);

            if (9 < ivalue) {
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            if (negative) {
                putCharMH.invokeExact(buffer, --index, (int)'-');
            }

            return index;
        }

        @Override
        public int size(long value) {
            boolean negative = value < 0;
            int sign = negative ? 1 : 0;

            if (!negative) {
                value = -value;
            }

            long precision = -10;
            for (int i = 1; i < 19; i++) {
                if (value > precision)
                    return i + sign;

                precision = 10 * precision;
            }

            return 19 + sign;
        }
    }

    /**
     * Digits class for hexadecimal digits.
     */
    static final class HexDigits implements Digits {
        private static final short[] DIGITS;

        /**
         * Singleton instance of HexDigits.
         */
        static final Digits INSTANCE = new HexDigits();

        static {
            short[] digits = new short[16 * 16];

            for (int i = 0; i < 16; i++) {
                short hi = (short) ((i < 10 ? i + '0' : i - 10 + 'a') << 8);

                for (int j = 0; j < 16; j++) {
                    short lo = (short) (j < 10 ? j + '0' : j - 10 + 'a');
                    digits[(i << 4) + j] = (short) (hi | lo);
                }
            }

            DIGITS = digits;
        }

        /**
         * Constructor.
         */
        private HexDigits() {
        }

        @Override
        public int digits(long value, byte[] buffer, int index,
                          MethodHandle putCharMH) throws Throwable {
            while ((value & ~0xFF) != 0) {
                int digits = DIGITS[(int) (value & 0xFF)];
                value >>>= 8;
                putCharMH.invokeExact(buffer, --index, digits & 0xFF);
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            int digits = DIGITS[(int) (value & 0xFF)];
            putCharMH.invokeExact(buffer, --index, digits & 0xFF);

            if (0xF < value) {
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            return index;
        }

        @Override
        public int size(long value) {
            return value == 0 ? 1 :
                    67 - Long.numberOfLeadingZeros(value) >> 2;
        }
    }

    /**
     * Digits class for octal digits.
     */
    static final class OctalDigits implements Digits {
        private static final short[] DIGITS;

        /**
         * Singleton instance of HexDigits.
         */
        static final Digits INSTANCE = new OctalDigits();

        static {
            short[] digits = new short[8 * 8];

            for (int i = 0; i < 8; i++) {
                short hi = (short) ((i + '0') << 8);

                for (int j = 0; j < 8; j++) {
                    short lo = (short) (j + '0');
                    digits[(i << 3) + j] = (short) (hi | lo);
                }
            }

            DIGITS = digits;
        }

        /**
         * Constructor.
         */
        private OctalDigits() {
        }

        @Override
        public int digits(long value, byte[] buffer, int index,
                          MethodHandle putCharMH) throws Throwable {
            while ((value & ~0x3F) != 0) {
                int digits = DIGITS[(int) (value & 0x3F)];
                value >>>= 6;
                putCharMH.invokeExact(buffer, --index, digits & 0xFF);
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            int digits = DIGITS[(int) (value & 0x3F)];
            putCharMH.invokeExact(buffer, --index, digits & 0xFF);

            if (0xF < value) {
                putCharMH.invokeExact(buffer, --index, digits >> 8);
            }

            return index;
        }

        @Override
        public int size(long value) {
            return (66 - Long.numberOfLeadingZeros(value)) / 3;
        }
    }

    /**
     * Decimal value format item.
     */
    static final class FormatItemDecimal implements StringConcatItem {
        private final char decimalSeparator;
        private final char groupingSeparator;
        private final char zeroDigit;
        private final char minusSign;
        private final int digitOffset;
        private final byte[] digits;
        private final int length;
        private final boolean isNegative;
        private final int width;
        private final byte prefixSign;
        private final byte suffixSign;
        private final int groupSize;
        private final long value;

        FormatItemDecimal(DecimalFormatSymbols dfs, int width, char sign,
                          int groupSize, long value) throws Throwable {
            this.decimalSeparator = dfs.getDecimalSeparator();
            this.groupingSeparator = dfs.getGroupingSeparator();
            this.zeroDigit = dfs.getZeroDigit();
            this.minusSign = dfs.getMinusSign();
            this.digitOffset = this.zeroDigit - '0';
            int length = DecimalDigits.INSTANCE.size(value);
            this.digits = new byte[length];
            DecimalDigits.INSTANCE.digits(value, this.digits, length, PUT_CHAR_DIGIT);
            this.isNegative = value < 0L;
            this.length = this.isNegative ? length - 1 : length;
            this.width = width;
            this.prefixSign = (byte)prefixSign(sign, isNegative);
            this.suffixSign = (byte)suffixSign(sign, isNegative);
            this.groupSize = groupSize;
            this.value = value;
        }

        private char prefixSign(char sign, boolean isNegative) {
            if (isNegative) {
                return sign == '(' ? '(' : minusSign;
            } else {
                return sign == '+' || sign == ' ' ? sign : '\0';
            }
        }

        private char suffixSign(char sign, boolean isNegative) {
            return isNegative && sign == '(' ? ')' : '\0';
        }

        private int signLength() {
            return (prefixSign != '\0' ? 1 : 0) + (suffixSign != '\0' ? 1 : 0);
        }

        private int groupLength() {
            return 0 < groupSize ? (length - 1) / groupSize : 0;
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder +
                    Integer.max(length + signLength() + groupLength(), width);
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);

            if (suffixSign != '\0') {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)suffixSign);
            }

            int groupIndex = groupSize;

            for (int i = 1; i <= length; i++) {
                if (0 < groupIndex && groupIndex-- == 0) {
                    putCharMH.invokeExact(buffer, (int)--lengthCoder,
                            (int)groupingSeparator);
                    groupIndex = groupSize;
                }

                putCharMH.invokeExact(buffer, (int)--lengthCoder,
                        digits[digits.length - i] + digitOffset);
            }

            for (int i = length + signLength() + groupLength(); i < width; i++) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'0');
            }

            if (prefixSign != '\0') {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)prefixSign);
            }

            return lengthCoder;
        }
    }

    /**
     * Hexadecimal format item.
     */
    static final class FormatItemHexadecimal implements StringConcatItem{
        private final int width;
        private final boolean hasPrefix;
        private final long value;
        private final int length;

        FormatItemHexadecimal(int width, boolean hasPrefix, long value) {
            this.width = width;
            this.hasPrefix = hasPrefix;
            this.value = value;
            this.length = HexDigits.INSTANCE.size(value);
        }

        private int prefixLength() {
            return hasPrefix ? 2 : 0;
        }

        private int zeroesLength() {
            return Integer.max(0, width - length - prefixLength());
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + length + prefixLength() + zeroesLength();
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);
            HexDigits.INSTANCE.digits(value, buffer, (int)lengthCoder, putCharMH);
            lengthCoder -= length;

            for (int i = 0; i < zeroesLength(); i++) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'0');
            }

            if (hasPrefix) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'x');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'0');
            }

            return lengthCoder;
        }
    }

    /**
     * Hexadecimal format item.
     */
    static final class FormatItemOctal implements StringConcatItem{
        private final int width;
        private final boolean hasPrefix;
        private final long value;
        private final int length;

        FormatItemOctal(int width, boolean hasPrefix, long value) {
            this.width = width;
            this.hasPrefix = hasPrefix;
            this.value = value;
            this.length = OctalDigits.INSTANCE.size(value);
        }

        private int prefixLength() {
            return hasPrefix && value != 0 ? 1 : 0;
        }

        private int zeroesLength() {
            return Integer.max(0, width - length - prefixLength());
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + length + prefixLength() + zeroesLength();
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);
            OctalDigits.INSTANCE.digits(value, buffer, (int)lengthCoder, putCharMH);
            lengthCoder -= length;

            for (int i = 0; i < zeroesLength(); i++) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'0');
            }

            if (hasPrefix && value != 0) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'0');
            }

            return lengthCoder;
        }
    }

    /**
     * Boolean format item.
     */
    static final class FormatItemBoolean implements StringConcatItem{
        private final boolean value;

        FormatItemBoolean(boolean value) {
            this.value = value;
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + (value ? "true".length() : "false".length());
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);

            if (value) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'e');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'u');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'r');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'t');
            } else {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'e');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'s');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'l');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'a');
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'f');
            }

            return lengthCoder;
         }
    }

    /**
     * Character format item.
     */
    static final class FormatItemCharacter implements StringConcatItem{
        private final char value;

        FormatItemCharacter(char value) {
            this.value = value;
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + 1;
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);
            putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)value);

            return lengthCoder;
        }
    }

    /**
     * String format item.
     */
    static final class FormatItemString implements StringConcatItem {
        private String value;

        FormatItemString(String value) {
            this.value = value;
        }

        @Override
        public long mix(long lengthCoder) {
            return stringMix(lengthCoder, value);
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            return stringPrepend(lengthCoder, buffer, value);
        }
    }

    /**
     * FormatSpecifier format item.
     */
    static final class FormatItemFormatSpecifier implements StringConcatItem {
        private StringBuilder sb;

        FormatItemFormatSpecifier(FormatSpecifier fs, Locale locale, Object value) {
            this.sb = new StringBuilder(64);
            Formatter formatter = new Formatter(this.sb, locale);

            try {
                fs.print(formatter, value, locale);
            } catch (IOException ex) {
                throw new AssertionError("FormatItemFormatSpecifier IOException", ex);
            }
        }

        FormatItemFormatSpecifier(Locale locale,
                                  int flags, int width, int precision,
                                  Formattable formattable) {
            this.sb = new StringBuilder(64);
            Formatter formatter = new Formatter(this.sb, locale);
            formattable.formatTo(formatter, flags, width, precision);
        }

        @Override
        public long mix(long lengthCoder) {
            return JLA.stringBuilderConcatMix(lengthCoder, sb);
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            return JLA.stringBuilderConcatPrepend(lengthCoder, buffer, sb);
        }
    }

    /**
     * Fill left format item.
     */
    static final class FormatItemFillLeft implements StringConcatItem{
        private final int length;
        private final int width;
        private final StringConcatItem item;

        FormatItemFillLeft(int width, StringConcatItem item) {
            this.length = (int)item.mix(0L);
            this.width = Integer.max(this.length, width);
            this.item = item;
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + width;
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);
            lengthCoder = item.prepend(lengthCoder, buffer);

            for (int i = length; i < width; i++) {
                putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)' ');
            }

            return lengthCoder;
        }
    }

    /**
     * Fill right format item.
     */
    static final class FormatItemFillRight implements StringConcatItem{
        private final int length;
        private final int width;
        private final StringConcatItem item;

        FormatItemFillRight(int width, StringConcatItem item) {
            this.length = (int)item.mix(0L);
            this.width = Integer.max(this.length, width);
            this.item = item;
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + width;
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            try {
                MethodHandle putCharMH = selectPutChar(lengthCoder);

                for (int i = length; i < width; i++) {
                    putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)' ');
                }

                lengthCoder = item.prepend(lengthCoder, buffer);

                return lengthCoder;
            } catch (Throwable ex) {
                throw new AssertionError("FormatItemFillRight prepend failed", ex);
            }
        }
    }


    /**
     * To upper case format item.
     */
    static final class FormatItemUpper implements StringConcatItem{
        private final StringConcatItem item;
        private final int length;

        FormatItemUpper(StringConcatItem item) {
            this.item = item;
            this.length = (int)item.mix(0L);
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + length;
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle getCharMH = selectGetChar(lengthCoder);
            MethodHandle putCharMH = selectPutChar(lengthCoder);
            lengthCoder = item.prepend(lengthCoder, buffer);
            int start = (int)lengthCoder;

            for (int i = 0; i < length; i++) {
                char ch = (char)getCharMH.invokeExact(buffer, start + i);
                putCharMH.invokeExact(buffer, start + i, (int)Character.toUpperCase(ch));
            }

            return lengthCoder;
        }
    }

    /**
     * Null format item.
     */
    static final class FormatItemNull implements StringConcatItem{
        FormatItemNull() {
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + "null".length();
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            MethodHandle putCharMH = selectPutChar(lengthCoder);

            putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'l');
            putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'l');
            putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'u');
            putCharMH.invokeExact(buffer, (int)--lengthCoder, (int)'n');

            return lengthCoder;
        }
    }
}
