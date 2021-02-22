/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

package sun.security.util.math;

import java.math.BigInteger;

/**
 * Default implementations of methods for {@code IntegerModuloP}.
 *
 * @see IntegerModuloP
 */
public abstract class AbstractElement implements IntegerModuloP {

    @Override
    public byte[] addModPowerTwo(IntegerModuloP b, int len) {
        byte[] result = new byte[len];
        addModPowerTwo(b, result);
        return result;
    }

    @Override
    public byte[] asByteArray(int len) {
        byte[] result = new byte[len];
        asByteArray(result);
        return result;
    }

    @Override
    public ImmutableIntegerModuloP multiplicativeInverse() {
        return pow(getField().getSize().subtract(BigInteger.valueOf(2)));
    }

    @Override
    public ImmutableIntegerModuloP subtract(IntegerModuloP b) {
        return add(b.additiveInverse());
    }

    @Override
    public ImmutableIntegerModuloP square() {
        return multiply(this);
    }

    @Override
    public ImmutableIntegerModuloP pow(BigInteger b) {
        //Public implementation is square and multiply
        MutableIntegerModuloP y = getField().get1().mutable();
        MutableIntegerModuloP x = mutable();
        int bitLength = b.bitLength();
        for (int bit = 0; bit < bitLength; bit++) {
            if (b.testBit(bit)) {
                // odd
                y.setProduct(x);
            }
            x.setSquare();
        }
        return y.fixed();
    }
}
