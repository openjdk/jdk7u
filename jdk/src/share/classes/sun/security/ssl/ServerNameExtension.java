/*
 * Copyright (c) 2006, 2011, Oracle and/or its affiliates. All rights reserved.
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

package sun.security.ssl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLProtocolException;

/*
 * [RFC4366] To facilitate secure connections to servers that host multiple
 * 'virtual' servers at a single underlying network address, clients MAY
 * include an extension of type "server_name" in the (extended) client hello.
 * The "extension_data" field of this extension SHALL contain "ServerNameList"
 * where:
 *
 *     struct {
 *         NameType name_type;
 *         select (name_type) {
 *             case host_name: HostName;
 *         } name;
 *     } ServerName;
 *
 *     enum {
 *         host_name(0), (255)
 *     } NameType;
 *
 *     opaque HostName<1..2^16-1>;
 *
 *     struct {
 *         ServerName server_name_list<1..2^16-1>
 *     } ServerNameList;
 */
final class ServerNameExtension extends HelloExtension {

    final static int NAME_HOST_NAME = 0;

    private List<ServerName> names;
    private int listLength;     // ServerNameList length

    ServerNameExtension(List<String> hostnames) throws IOException {
        super(ExtensionType.EXT_SERVER_NAME);

        listLength = 0;
        names = new ArrayList<ServerName>(hostnames.size());
        for (String hostname : hostnames) {
            if (hostname != null && hostname.length() != 0) {
                // we only support DNS hostname now.
                ServerName serverName =
                        new ServerName(NAME_HOST_NAME, hostname);
                names.add(serverName);
                listLength += serverName.length;
            }
        }

        // As we only support DNS hostname now, the hostname list must
        // not contain more than one hostname
        if (names.size() > 1) {
            throw new SSLProtocolException(
                    "The ServerNameList MUST NOT contain more than " +
                    "one name of the same name_type");
        }

        // We only need to add "server_name" extension in ClientHello unless
        // we support SNI in server side in the future. It is possible that
        // the SNI is empty in ServerHello. As we don't support SNI in
        // ServerHello now, we will throw exception for empty list for now.
        if (listLength == 0) {
            throw new SSLProtocolException(
                    "The ServerNameList cannot be empty");
        }
    }

    ServerNameExtension(HandshakeInStream s, int len)
            throws IOException {
        super(ExtensionType.EXT_SERVER_NAME);

        int remains = len;
        if (len >= 2) {    // "server_name" extension in ClientHello
            listLength = s.getInt16();     // ServerNameList length
            if (listLength == 0 || listLength + 2 != len) {
                throw new SSLProtocolException(
                        "Invalid " + type + " extension");
            }

            remains -= 2;
            names = new ArrayList<ServerName>();
            while (remains > 0) {
                ServerName name = new ServerName(s);
                names.add(name);
                remains -= name.length;

                // we may need to check the duplicated ServerName type
            }
        } else if (len == 0) {     // "server_name" extension in ServerHello
            listLength = 0;
            names = Collections.<ServerName>emptyList();
        }

        if (remains != 0) {
            throw new SSLProtocolException("Invalid server_name extension");
        }
    }

    static class ServerName {
        final int length;
        final int type;
        final byte[] data;
        final String hostname;

        ServerName(int type, String hostname) throws IOException {
            this.type = type;                       // NameType
            this.hostname = hostname;
            this.data = hostname.getBytes("UTF8");  // HostName
            this.length = data.length + 3;          // NameType: 1 byte
                                                    // HostName length: 2 bytes
        }

        ServerName(HandshakeInStream s) throws IOException {
            type = s.getInt8();         // NameType
            data = s.getBytes16();      // HostName (length read in getBytes16)
            length = data.length + 3;   // NameType: 1 byte
                                        // HostName length: 2 bytes
            if (type == NAME_HOST_NAME) {
                hostname = new String(data, "UTF8");
            } else {
                hostname = null;
            }
        }

        public String toString() {
            if (type == NAME_HOST_NAME) {
                return "host_name: " + hostname;
            } else {
                return "unknown-" + type + ": " + Debug.toString(data);
            }
        }
    }

    int length() {
        return listLength == 0 ? 4 : 6 + listLength;
    }

    void send(HandshakeOutStream s) throws IOException {
        s.putInt16(type.id);
        s.putInt16(listLength + 2);
        if (listLength != 0) {
            s.putInt16(listLength);

            for (ServerName name : names) {
                s.putInt8(name.type);           // NameType
                s.putBytes16(name.data);        // HostName
            }
        }
    }

    public String toString() {
        StringBuffer buffer = new StringBuffer();
        for (ServerName name : names) {
            buffer.append("[" + name + "]");
        }

        return "Extension " + type + ", server_name: " + buffer;
    }
}
