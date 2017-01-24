/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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


package sun.security.util;

/**
 * Utility class for holding parsing methods that can't be added
 * to the classes which form part of the language specification.
 */
public final class Parsing {

    /**
     * Parses the string argument as an unsigned integer in the radix
     * specified by the second argument.  An unsigned integer maps the
     * values usually associated with negative numbers to positive
     * numbers larger than {@code MAX_VALUE}.
     *
     * The characters in the string must all be digits of the
     * specified radix (as determined by whether {@link
     * java.lang.Character#digit(char, int)} returns a nonnegative
     * value), except that the first character may be an ASCII plus
     * sign {@code '+'} ({@code '\u005Cu002B'}). The resulting
     * integer value is returned.
     *
     * <p>An exception of type {@code NumberFormatException} is
     * thrown if any of the following situations occurs:
     * <ul>
     * <li>The first argument is {@code null} or is a string of
     * length zero.
     *
     * <li>The radix is either smaller than
     * {@link java.lang.Character#MIN_RADIX} or
     * larger than {@link java.lang.Character#MAX_RADIX}.
     *
     * <li>Any character of the string is not a digit of the specified
     * radix, except that the first character may be a plus sign
     * {@code '+'} ({@code '\u005Cu002B'}) provided that the
     * string is longer than length 1.
     *
     * <li>The value represented by the string is larger than the
     * largest unsigned {@code int}, 2<sup>32</sup>-1.
     *
     * </ul>
     *
     *
     * @param      s   the {@code String} containing the unsigned integer
     *                  representation to be parsed
     * @param      radix   the radix to be used while parsing {@code s}.
     * @return     the integer represented by the string argument in the
     *             specified radix.
     * @throws     NumberFormatException if the {@code String}
     *             does not contain a parsable {@code int}.
     */
    public static int parseUnsignedInt(String s, int radix)
                throws NumberFormatException {
        if (s == null)  {
            throw new NumberFormatException("null");
        }

        int len = s.length();
        if (len > 0) {
            char firstChar = s.charAt(0);
            if (firstChar == '-') {
                throw new
                    NumberFormatException(String.format("Illegal leading minus sign " +
                                                       "on unsigned string %s.", s));
            } else {
                if (len <= 5 || // Integer.MAX_VALUE in Character.MAX_RADIX is 6 digits
                    (radix == 10 && len <= 9) ) { // Integer.MAX_VALUE in base 10 is 10 digits
                    return Integer.parseInt(s, radix);
                } else {
                    long ell = Long.parseLong(s, radix);
                    if ((ell & 0xffff_ffff_0000_0000L) == 0) {
                        return (int) ell;
                    } else {
                        throw new
                            NumberFormatException(String.format("String value %s exceeds " +
                                                                "range of unsigned int.", s));
                    }
                }
            }
        } else {
            throw new NumberFormatException("For input string: \"" + s + "\"");
        }
    }

    /**
     * Parses the string argument as an unsigned decimal integer. The
     * characters in the string must all be decimal digits, except
     * that the first character may be an an ASCII plus sign {@code
     * '+'} ({@code '\u005Cu002B'}). The resulting integer value
     * is returned, exactly as if the argument and the radix 10 were
     * given as arguments to the {@link
     * #parseUnsignedInt(java.lang.String, int)} method.
     *
     * @param s   a {@code String} containing the unsigned {@code int}
     *            representation to be parsed
     * @return    the unsigned integer value represented by the argument in decimal.
     * @throws    NumberFormatException  if the string does not contain a
     *            parsable unsigned integer.
     */
    public static int parseUnsignedInt(String s) throws NumberFormatException {
        return parseUnsignedInt(s, 10);
    }

}
