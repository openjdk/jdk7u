/*
 * Copyright 2017 Red Hat, Inc.
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
package sun.misc;

import java.security.cert.Certificate;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStore.TrustedCertificateEntry;
import java.security.PrivateKey;

import javax.crypto.SecretKey;

import java.util.Set;

import sun.security.pkcs12.PKCS12Attribute;

/**
 * Shared secret interface to allow us
 * to create key entries which hold a set of
 * PKCS12Attribute objects.
 */
public interface JavaSecurityKeyStoreAccess {

    /**
     * Constructs a {@code PrivateKeyEntry} with a {@code PrivateKey} and
     * corresponding certificate chain and associated entry attributes.
     *
     * <p> The specified {@code chain} and {@code attributes} are cloned
     * before they are stored in the new {@code PrivateKeyEntry} object.
     *
     * @param privateKey the {@code PrivateKey}
     * @param chain an array of {@code Certificate}s
     *      representing the certificate chain.
     *      The chain must be ordered and contain a
     *      {@code Certificate} at index 0
     *      corresponding to the private key.
     * @param attributes the attributes
     *
     * @exception NullPointerException if {@code privateKey}, {@code chain}
     *      or {@code attributes} is {@code null}
     * @exception IllegalArgumentException if the specified chain has a
     *      length of 0, if the specified chain does not contain
     *      {@code Certificate}s of the same type,
     *      or if the {@code PrivateKey} algorithm
     *      does not match the algorithm of the {@code PublicKey}
     *      in the end entity {@code Certificate} (at index 0)
     *
     * @since 1.8
     */
    PrivateKeyEntry constructPrivateKeyEntry(PrivateKey privateKey, Certificate[] chain,
                                             Set<PKCS12Attribute> attributes);

    /**
     * Retrieves the attributes associated with a {@code PrivateKeyEntry}.
     * <p>
     *
     * @return an unmodifiable {@code Set} of attributes, possibly empty
     *
     * @since 1.8
     */
    Set<PKCS12Attribute> getPrivateKeyEntryAttributes(PrivateKeyEntry entry);

    /**
     * Constructs a {@code SecretKeyEntry} with a {@code SecretKey} and
     * associated entry attributes.
     *
     * <p> The specified {@code attributes} is cloned before it is stored
     * in the new {@code SecretKeyEntry} object.
     *
     * @param secretKey the {@code SecretKey}
     * @param attributes the attributes
     *
     * @exception NullPointerException if {@code secretKey} or
     *     {@code attributes} is {@code null}
     *
     * @since 1.8
     */
    SecretKeyEntry constructSecretKeyEntry(SecretKey secretKey, Set<PKCS12Attribute> attributes);

    /**
     * Retrieves the attributes associated with a {@code SecretKeyEntry}.
     * <p>
     *
     * @return an unmodifiable {@code Set} of attributes, possibly empty
     *
     * @since 1.8
     */
    Set<PKCS12Attribute> getSecretKeyEntryAttributes(SecretKeyEntry entry);

    /**
     * Constructs a {@code TrustedCertificateEntry} with a
     * trusted {@code Certificate} and associated entry attributes.
     *
     * <p> The specified {@code attributes} is cloned before it is stored
     * in the new {@code TrustedCertificateEntry} object.
     *
     * @param trustedCert the trusted {@code Certificate}
     * @param attributes the attributes
     *
     * @exception NullPointerException if {@code trustedCert} or
     *     {@code attributes} is {@code null}
     *
     * @since 1.8
     */
    TrustedCertificateEntry constructTrustedCertificateEntry(Certificate trustedCert,
                                                             Set<PKCS12Attribute> attributes);

    /**
     * Retrieves the attributes associated with a {@code TrustedCertificateEntry}.
     * <p>
     *
     * @return an unmodifiable {@code Set} of attributes, possibly empty
     *
     * @since 1.8
     */
    Set<PKCS12Attribute> getTrustedCertificateEntryAttributes(TrustedCertificateEntry entry);
}
