/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Common library for various security test helper functions.
 */
public final class SecurityUtils {

    private static String getCacerts() {
        String sep = File.separator;
        return System.getProperty("java.home") + sep
                + "lib" + sep + "security" + sep + "cacerts";
    }

    /**
     * Returns the cacerts keystore with the configured CA certificates.
     */
    public static KeyStore getCacertsKeyStore() throws Exception {
        File file = new File(getCacerts());
        if (!file.exists()) {
            return null;
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (FileInputStream fis = new FileInputStream(file)) {
            ks.load(fis, null);
        }
        return ks;
    }

    /**
     * Removes the specified protocols from the jdk.tls.disabledAlgorithms
     * security property.
     */
    public static void removeFromDisabledTlsAlgs(String... protocols) {
        List<String> protocolsList = Arrays.asList(protocols);
        protocolsList = Collections.unmodifiableList(protocolsList);
        removeFromDisabledAlgs("jdk.tls.disabledAlgorithms",
                               protocolsList);
    }

    private static void removeFromDisabledAlgs(String prop, List<String> algs) {
        String value = Security.getProperty(prop);
        List<String> disabledAlgs = Arrays.asList(value.split(","));

        StringBuilder newValue = new StringBuilder();
        for (String alg : disabledAlgs) {
            alg = alg.trim();
            if (!algs.contains(alg)) {
                if (newValue.length() != 0) {
                    newValue.append(", ");
                }
                newValue.append(alg);
            }
        }

        Security.setProperty(prop, newValue.toString());
    }

    private SecurityUtils() {}
}
