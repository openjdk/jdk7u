/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 8020842
 * @summary SNIHostName does not throw IAE when hostname ends
 *          with a trailing dot
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import sun.security.util.HostnameChecker;

public class IllegalSNIName {

    private static final boolean DEBUG = true;

    public static void main(String[] args) throws Throwable {
        String[] illegalNames = {
                "example\u3002\u3002com",
                "example..com",
                "com\u3002",
                "com.",
                "."
            };

        Method m = HostnameChecker.class.getDeclaredMethod("checkHostName",
                                                           String.class);
        m.setAccessible(true);
        // Old versions of HostnameChecker.checkHostName are not static
        // so we need an instance of the class
        Object instance = null;
        if (!Modifier.isStatic(m.getModifiers())) {
            instance = HostnameChecker.getInstance(HostnameChecker.TYPE_TLS);
        }
        for (String name : illegalNames) {
            Throwable t = null;
            try {
                if (DEBUG) {
                    System.err.println("Checking " + name);
                }
                m.invoke(instance, name);
                throw new Exception(
                    "Expected to get IllegalArgumentException for " + name);
            } catch (IllegalArgumentException iae) {
                // That's the right behavior.
                t = iae;
            } catch (InvocationTargetException ite) {
                Throwable target = ite.getTargetException();
                if (!(target instanceof IllegalArgumentException)) {
                    throw target;
                } else {
                    t = target;
                }
            }
            if (DEBUG && t != null) {
                System.err.println(name + " correctly threw: ");
                t.printStackTrace();
            }
        }
    }
}
