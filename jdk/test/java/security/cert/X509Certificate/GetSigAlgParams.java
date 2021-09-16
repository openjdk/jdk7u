/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * Certificate used in the test.
 *
 * Data:
 *     Version: 3 (0x2)
 *     Serial Number: 1451881911 (0x5689f5b7)
 *     Signature Algorithm: id-ecPublicKey
 *     Issuer: CN = Me
 *     Validity
 *         Not Before: Mar 16 16:27:19 2021 GMT
 *         Not After : Mar 16 16:28:59 2021 GMT
 *     Subject: CN = Me
 *     Subject Public Key Info:
 *         Public Key Algorithm: id-ecPublicKey
 *             Public-Key: (521 bit)
 *             pub:
 *                 04:00:cc:1a:be:7f:45:1c:b7:3c:ff:4e:d5:2c:1b:
 *                 7e:3c:81:c0:dd:f7:11:8b:3f:d8:b1:01:32:9d:bb:
 *                 64:d5:3f:1d:01:a3:bf:ab:99:b3:c9:0e:52:04:e1:
 *                 56:e8:51:8f:f5:03:bd:8d:3f:ab:1c:2a:35:87:91:
 *                 a0:11:83:fb:c9:45:69:00:f9:c6:d0:42:1d:19:49:
 *                 52:7f:10:9b:2a:24:f1:0b:56:fa:e5:ab:19:3b:44:
 *                 04:01:dd:9b:99:f9:44:60:8d:97:f4:7a:0e:54:c0:
 *                 28:e9:53:a8:8c:f0:22:da:2b:ae:e3:dc:5b:56:20:
 *                 0d:8f:88:30:80:82:b7:2a:3f:f8:49:fb:27
 *             ASN1 OID: secp521r1
 *             NIST CURVE: P-521
 * Signature Algorithm: id-ecPublicKey
 *      30:81:88:02:42:00:88:b6:e2:db:2f:d1:e3:15:b5:7b:46:e2:
 *      6b:66:56:c0:a8:75:55:c0:ae:5a:41:30:3f:e3:ca:31:7e:e2:
 *      7c:70:c8:5e:30:47:b8:3b:4b:e5:dd:76:3d:4a:d6:41:9b:88:
 *      be:a5:0c:41:a0:1c:e1:15:86:94:b4:a2:be:40:92:c9:3a:02:
 *      42:01:2a:a9:37:f3:ff:08:2f:10:ba:b0:2d:6b:0b:e1:e2:5c:
 *      eb:c6:98:b0:db:42:fb:2f:82:65:34:d2:68:b3:ab:0d:9f:2e:
 *      23:ed:0a:ba:4b:a2:3b:1c:07:35:ab:ad:29:3c:54:ea:05:85:
 *      08:40:4a:72:8c:b3:c8:ea:95:05:95:41:b0
 */

/*
 * @test
 * @bug 8259428
 * @summary Verify X509Certificate.getSigAlgParams() returns new array each
 *          time it is called
 */

import java.security.cert.X509Certificate;
import sun.security.x509.X509CertImpl;
import sun.misc.BASE64Decoder;

public class GetSigAlgParams {

    public static void main(String[] args) throws Exception {

        String cert =
            "MIIBnTCB+aADAgECAgRWifW3MBAGByqGSM49AgEGBSuBBAAjMA0xCzAJBgNVBAMTAk1lMB" +
            "4XDTIxMDMxNjE2MjcxOVoXDTIxMDMxNjE2Mjg1OVowDTELMAkGA1UEAxMCTWUwgZswEAYH" +
            "KoZIzj0CAQYFK4EEACMDgYYABADMGr5/RRy3PP9O1SwbfjyBwN33EYs/2LEBMp27ZNU/HQ" +
            "Gjv6uZs8kOUgThVuhRj/UDvY0/qxwqNYeRoBGD+8lFaQD5xtBCHRlJUn8Qmyok8QtW+uWr" +
            "GTtEBAHdm5n5RGCNl/R6DlTAKOlTqIzwItorruPcW1YgDY+IMICCtyo/+En7JzAQBgcqhk" +
            "jOPQIBBgUrgQQAIwOBjAAwgYgCQgCItuLbL9HjFbV7RuJrZlbAqHVVwK5aQTA/48oxfuJ8" +
            "cMheMEe4O0vl3XY9StZBm4i+pQxBoBzhFYaUtKK+QJLJOgJCASqpN/P/CC8QurAtawvh4l" +
            "zrxpiw20L7L4JlNNJos6sNny4j7Qq6S6I7HAc1q60pPFTqBYUIQEpyjLPI6pUFlUGw";

        BASE64Decoder base64 = new BASE64Decoder();
        byte[] encodedCert = base64.decodeBuffer(cert);
        X509Certificate c = new X509CertImpl(encodedCert);

        if (c.getSigAlgParams() == c.getSigAlgParams()) {
            throw new Exception("Encoded params are the same byte array");
        }
    }
}
