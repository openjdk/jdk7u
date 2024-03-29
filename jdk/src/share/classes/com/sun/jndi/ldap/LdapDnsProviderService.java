/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.jndi.ldap;

import java.security.PrivilegedAction;
import java.util.*;
import javax.naming.NamingException;
import com.sun.jndi.ldap.spi.LdapDnsProvider;
import com.sun.jndi.ldap.spi.LdapDnsProviderResult;
import sun.misc.JavaSecurityAccess;
import sun.misc.SharedSecrets;
import sun.security.util.SecurityConstants;

/**
 * The {@code LdapDnsProviderService} is responsible for creating and providing
 * access to the registered {@code LdapDnsProvider}s. The {@link ServiceLoader}
 * is used to find and register any implementations of {@link LdapDnsProvider}.
 *
 * <p> Instances of this class are safe for use by multiple threads.
 */
final class LdapDnsProviderService {

    private static volatile LdapDnsProviderService service;
    private static final Object LOCK = new int[0];
    private final ServiceLoader<LdapDnsProvider> providers;

    /**
     * Creates a new instance of LdapDnsProviderService
     */
    private LdapDnsProviderService() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            providers = ServiceLoader.load(
                    LdapDnsProvider.class,
                    ClassLoader.getSystemClassLoader());
        } else {
            final PrivilegedAction<ServiceLoader<LdapDnsProvider>> pa =
                    new PrivilegedAction<ServiceLoader<LdapDnsProvider>>() {
                        @Override
                        public ServiceLoader<LdapDnsProvider> run() {
                            return ServiceLoader.load(
                                    LdapDnsProvider.class,
                                    ClassLoader.getSystemClassLoader());
                        }
                    };

            JavaSecurityAccess jsa = SharedSecrets.getJavaSecurityAccess();
            providers = jsa.doPrivileged(
                    pa,
                    null,
                    new RuntimePermission("ldapDnsProvider"),
                    SecurityConstants.GET_CLASSLOADER_PERMISSION);
        }
    }

    /**
     * Retrieves the singleton instance of LdapDnsProviderService.
     */
    static LdapDnsProviderService getInstance() {
        if (service != null) return service;
        synchronized (LOCK) {
            if (service != null) return service;
            service = new LdapDnsProviderService();
        }
        return service;
    }

    /**
     * Retrieves result from the first provider that successfully resolves
     * the endpoints. If no results are found when calling installed
     * subclasses of {@code LdapDnsProvider} then this method will fall back
     * to the {@code DefaultLdapDnsProvider}.
     *
     * @throws NamingException if the {@code url} in not valid or an error
     *                         occurred while performing the lookup.
     */
    LdapDnsProviderResult lookupEndpoints(String url, Hashtable<?,?> env)
        throws NamingException
    {
        LdapDnsProviderResult result = null;
        Hashtable<?, ?> envCopy = new Hashtable<>(env);

        synchronized (LOCK) {
            Iterator<LdapDnsProvider> iterator = providers.iterator();
            while (result == null && iterator.hasNext()) {
                result = iterator.next().lookupEndpoints(url, envCopy);
                if (result != null && result.getEndpoints().isEmpty()) {
                    result = null;
                }
            }
        }

        if (result == null) {
            result = new DefaultLdapDnsProvider().lookupEndpoints(url, env);
            if (result == null) {
                return new LdapDnsProviderResult("", Collections.<String>emptyList());
            }
        }

        return result;
    }

}
