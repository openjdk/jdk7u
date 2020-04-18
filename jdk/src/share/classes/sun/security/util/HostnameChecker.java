/*
 * Copyright (c) 2002, 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.io.InputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;

import java.security.Principal;
import java.security.cert.*;
import java.text.Normalizer;

import javax.security.auth.x500.X500Principal;

import sun.net.idn.Punycode;
import sun.net.idn.StringPrep;
import sun.net.util.IPAddressUtil;
import sun.security.ssl.Krb5Helper;
import sun.security.x509.X500Name;
import sun.text.normalizer.UCharacterIterator;

/**
 * Class to check hostnames against the names specified in a certificate as
 * required for TLS and LDAP.
 *
 */
public class HostnameChecker {

    // Constant for a HostnameChecker for TLS
    public final static byte TYPE_TLS = 1;
    private final static HostnameChecker INSTANCE_TLS =
                                        new HostnameChecker(TYPE_TLS);

    // Constant for a HostnameChecker for LDAP
    public final static byte TYPE_LDAP = 2;
    private final static HostnameChecker INSTANCE_LDAP =
                                        new HostnameChecker(TYPE_LDAP);

    // constants for subject alt names of type DNS and IP
    private final static int ALTNAME_DNS = 2;
    private final static int ALTNAME_IP  = 7;

    // the algorithm to follow to perform the check. Currently unused.
    private final byte checkType;

    private HostnameChecker(byte checkType) {
        this.checkType = checkType;
    }

    /**
     * Get a HostnameChecker instance. checkType should be one of the
     * TYPE_* constants defined in this class.
     */
    public static HostnameChecker getInstance(byte checkType) {
        if (checkType == TYPE_TLS) {
            return INSTANCE_TLS;
        } else if (checkType == TYPE_LDAP) {
            return INSTANCE_LDAP;
        }
        throw new IllegalArgumentException("Unknown check type: " + checkType);
    }

    /**
     * Perform the check.
     *
     * @exception CertificateException if the name does not match any of
     * the names specified in the certificate
     */
    public void match(String expectedName, X509Certificate cert)
            throws CertificateException {
        if (isIpAddress(expectedName)) {
           matchIP(expectedName, cert);
        } else {
           matchDNS(expectedName, cert);
        }
    }

    /**
     * Perform the check for Kerberos.
     */
    public static boolean match(String expectedName, Principal principal) {
        String hostName = getServerName(principal);
        return (expectedName.equalsIgnoreCase(hostName));
    }

    /**
     * Return the Server name from Kerberos principal.
     */
    public static String getServerName(Principal principal) {
        return Krb5Helper.getPrincipalHostName(principal);
    }

    /**
     * Test whether the given hostname looks like a literal IPv4 or IPv6
     * address. The hostname does not need to be a fully qualified name.
     *
     * This is not a strict check that performs full input validation.
     * That means if the method returns true, name need not be a correct
     * IP address, rather that it does not represent a valid DNS hostname.
     * Likewise for IP addresses when it returns false.
     */
    private static boolean isIpAddress(String name) {
        if (IPAddressUtil.isIPv4LiteralAddress(name) ||
            IPAddressUtil.isIPv6LiteralAddress(name)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if the certificate allows use of the given IP address.
     *
     * From RFC2818:
     * In some cases, the URI is specified as an IP address rather than a
     * hostname. In this case, the iPAddress subjectAltName must be present
     * in the certificate and must exactly match the IP in the URI.
     */
    private static void matchIP(String expectedIP, X509Certificate cert)
            throws CertificateException {
        Collection<List<?>> subjAltNames = cert.getSubjectAlternativeNames();
        if (subjAltNames == null) {
            throw new CertificateException
                                ("No subject alternative names present");
        }
        for (List<?> next : subjAltNames) {
            // For IP address, it needs to be exact match
            if (((Integer)next.get(0)).intValue() == ALTNAME_IP) {
                String ipAddress = (String)next.get(1);
                if (expectedIP.equalsIgnoreCase(ipAddress)) {
                    return;
                } else {
                    // compare InetAddress objects in order to ensure
                    // equality between a long IPv6 address and its
                    // abbreviated form.
                    try {
                        if (InetAddress.getByName(expectedIP).equals(
                                InetAddress.getByName(ipAddress))) {
                            return;
                        }
                    } catch (UnknownHostException e) {
                    } catch (SecurityException e) {}
                }
            }
        }
        throw new CertificateException("No subject alternative " +
                        "names matching " + "IP address " +
                        expectedIP + " found");
    }

    /**
     * Check if the certificate allows use of the given DNS name.
     *
     * From RFC2818:
     * If a subjectAltName extension of type dNSName is present, that MUST
     * be used as the identity. Otherwise, the (most specific) Common Name
     * field in the Subject field of the certificate MUST be used. Although
     * the use of the Common Name is existing practice, it is deprecated and
     * Certification Authorities are encouraged to use the dNSName instead.
     *
     * Matching is performed using the matching rules specified by
     * [RFC2459].  If more than one identity of a given type is present in
     * the certificate (e.g., more than one dNSName name, a match in any one
     * of the set is considered acceptable.)
     */
    private void matchDNS(String expectedName, X509Certificate cert)
            throws CertificateException {
        // Check that the expected name is a valid domain name.
        try {
            // Using the checking taken from OpenJDK 8's SNIHostName
            checkHostName(expectedName);
        } catch (IllegalArgumentException iae) {
            throw new CertificateException(
                "Illegal given domain name: " + expectedName, iae);
        }

        Collection<List<?>> subjAltNames = cert.getSubjectAlternativeNames();
        if (subjAltNames != null) {
            boolean foundDNS = false;
            for ( List<?> next : subjAltNames) {
                if (((Integer)next.get(0)).intValue() == ALTNAME_DNS) {
                    foundDNS = true;
                    String dnsName = (String)next.get(1);
                    if (isMatched(expectedName, dnsName)) {
                        return;
                    }
                }
            }
            if (foundDNS) {
                // if certificate contains any subject alt names of type DNS
                // but none match, reject
                throw new CertificateException("No subject alternative DNS "
                        + "name matching " + expectedName + " found.");
            }
        }
        X500Name subjectName = getSubjectX500Name(cert);
        DerValue derValue = subjectName.findMostSpecificAttribute
                                                    (X500Name.commonName_oid);
        if (derValue != null) {
            try {
                String cname = derValue.getAsString();
                if (!Normalizer.isNormalized(cname, Normalizer.Form.NFKC)) {
                    throw new CertificateException("Not a formal name "
                            + cname);
                }
                if (isMatched(expectedName, cname)) {
                    return;
                }
            } catch (IOException e) {
                // ignore
            }
        }
        String msg = "No name matching " + expectedName + " found";
        throw new CertificateException(msg);
    }


    /**
     * Return the subject of a certificate as X500Name, by reparsing if
     * necessary. X500Name should only be used if access to name components
     * is required, in other cases X500Principal is to be preferred.
     *
     * This method is currently used from within JSSE, do not remove.
     */
    public static X500Name getSubjectX500Name(X509Certificate cert)
            throws CertificateParsingException {
        try {
            Principal subjectDN = cert.getSubjectDN();
            if (subjectDN instanceof X500Name) {
                return (X500Name)subjectDN;
            } else {
                X500Principal subjectX500 = cert.getSubjectX500Principal();
                return new X500Name(subjectX500.getEncoded());
            }
        } catch (IOException e) {
            throw(CertificateParsingException)
                new CertificateParsingException().initCause(e);
        }
    }


    /**
     * Returns true if name matches against template.<p>
     *
     * The matching is performed as per RFC 2818 rules for TLS and
     * RFC 2830 rules for LDAP.<p>
     *
     * The <code>name</code> parameter should represent a DNS name.  The
     * <code>template</code> parameter may contain the wildcard character '*'.
     */
    private boolean isMatched(String name, String template) {
        // check the validity of the domain name template.
        try {
            // Replacing wildcard character '*' with 'z' so as to check
            // the domain name template validity.
            //
            // Using the checking taken from OpenJDK 8's SNIHostName
            checkHostName(template.replace('*', 'z'));
        } catch (IllegalArgumentException iae) {
            // It would be nice to add debug log if not matching.
            return false;
        }

        if (checkType == TYPE_TLS) {
            return matchAllWildcards(name, template);
        } else if (checkType == TYPE_LDAP) {
            return matchLeftmostWildcard(name, template);
        } else {
            return false;
        }
    }


    /**
     * Returns true if name matches against template.<p>
     *
     * According to RFC 2818, section 3.1 -
     * Names may contain the wildcard character * which is
     * considered to match any single domain name component
     * or component fragment.
     * E.g., *.a.com matches foo.a.com but not
     * bar.foo.a.com. f*.com matches foo.com but not bar.com.
     */
    private static boolean matchAllWildcards(String name,
         String template) {
        name = name.toLowerCase(Locale.ENGLISH);
        template = template.toLowerCase(Locale.ENGLISH);
        StringTokenizer nameSt = new StringTokenizer(name, ".");
        StringTokenizer templateSt = new StringTokenizer(template, ".");

        if (nameSt.countTokens() != templateSt.countTokens()) {
            return false;
        }

        while (nameSt.hasMoreTokens()) {
            if (!matchWildCards(nameSt.nextToken(),
                        templateSt.nextToken())) {
                return false;
            }
        }
        return true;
    }


    /**
     * Returns true if name matches against template.<p>
     *
     * As per RFC 2830, section 3.6 -
     * The "*" wildcard character is allowed.  If present, it applies only
     * to the left-most name component.
     * E.g. *.bar.com would match a.bar.com, b.bar.com, etc. but not
     * bar.com.
     */
    private static boolean matchLeftmostWildcard(String name,
                         String template) {
        name = name.toLowerCase(Locale.ENGLISH);
        template = template.toLowerCase(Locale.ENGLISH);

        // Retreive leftmost component
        int templateIdx = template.indexOf(".");
        int nameIdx = name.indexOf(".");

        if (templateIdx == -1)
            templateIdx = template.length();
        if (nameIdx == -1)
            nameIdx = name.length();

        if (matchWildCards(name.substring(0, nameIdx),
            template.substring(0, templateIdx))) {

            // match rest of the name
            return template.substring(templateIdx).equals(
                        name.substring(nameIdx));
        } else {
            return false;
        }
    }


    /**
     * Returns true if the name matches against the template that may
     * contain wildcard char * <p>
     */
    private static boolean matchWildCards(String name, String template) {

        int wildcardIdx = template.indexOf("*");
        if (wildcardIdx == -1)
            return name.equals(template);

        boolean isBeginning = true;
        String beforeWildcard = "";
        String afterWildcard = template;

        while (wildcardIdx != -1) {

            // match in sequence the non-wildcard chars in the template.
            beforeWildcard = afterWildcard.substring(0, wildcardIdx);
            afterWildcard = afterWildcard.substring(wildcardIdx + 1);

            int beforeStartIdx = name.indexOf(beforeWildcard);
            if ((beforeStartIdx == -1) ||
                        (isBeginning && beforeStartIdx != 0)) {
                return false;
            }
            isBeginning = false;

            // update the match scope
            name = name.substring(beforeStartIdx + beforeWildcard.length());
            wildcardIdx = afterWildcard.indexOf("*");
        }
        return name.endsWith(afterWildcard);
    }

    // check the validity of the string hostname
    private static void checkHostName(String hostname) {
        hostname = toASCII(Objects.requireNonNull(hostname,
                                                  "Server name value of host_name cannot be null"),
                           IDN.USE_STD3_ASCII_RULES);
        // Check it can be encoded to ASCII
        hostname.getBytes(StandardCharsets.US_ASCII);

        if (hostname.isEmpty()) {
            throw new IllegalArgumentException(
                "Server name value of host_name cannot be empty");
        }

        if (hostname.endsWith(".")) {
            throw new IllegalArgumentException(
                "Server name value of host_name cannot have the trailing dot");
        }
    }

    /*
     * Local versions of toASCII(String,int), toASCIIInternal(String, int)
     * and their helper methods with 8020842 fix added. Can't change the
     * public version due to compatibility.
     */

    // ACE Prefix is "xn--"
    private static final String ACE_PREFIX = "xn--";
    private static final int ACE_PREFIX_LENGTH = ACE_PREFIX.length();

    private static final int MAX_LABEL_LENGTH   = 63;

    // single instance of nameprep
    private static StringPrep namePrep = null;

    static {
        InputStream stream = null;

        try {
            final String IDN_PROFILE = "uidna.spp";
            if (System.getSecurityManager() != null) {
                stream = AccessController.doPrivileged(new PrivilegedAction<InputStream>() {
                    public InputStream run() {
                        return StringPrep.class.getResourceAsStream(IDN_PROFILE);
                    }
                });
            } else {
                stream = StringPrep.class.getResourceAsStream(IDN_PROFILE);
            }

            namePrep = new StringPrep(stream);
            stream.close();
        } catch (IOException e) {
            // should never reach here
            assert false;
        }
    }

    /**
     * Translates a string from Unicode to ASCII Compatible Encoding (ACE),
     * as defined by the ToASCII operation of <a href="http://www.ietf.org/rfc/rfc3490.txt">RFC 3490</a>.
     *
     * <p>ToASCII operation can fail. ToASCII fails if any step of it fails.
     * If ToASCII operation fails, an IllegalArgumentException will be thrown.
     * In this case, the input string should not be used in an internationalized domain name.
     *
     * <p> A label is an individual part of a domain name. The original ToASCII operation,
     * as defined in RFC 3490, only operates on a single label. This method can handle
     * both label and entire domain name, by assuming that labels in a domain name are
     * always separated by dots. The following characters are recognized as dots:
     * &#0092;u002E (full stop), &#0092;u3002 (ideographic full stop), &#0092;uFF0E (fullwidth full stop),
     * and &#0092;uFF61 (halfwidth ideographic full stop). if dots are
     * used as label separators, this method also changes all of them to &#0092;u002E (full stop)
     * in output translated string.
     *
     * @param input     the string to be processed
     * @param flag      process flag; can be 0 or any logical OR of possible flags
     *
     * @return          the translated {@code String}
     *
     * @throws IllegalArgumentException   if the input string doesn't conform to RFC 3490 specification
     */
    private static String toASCII(String input, int flag)
    {
        int p = 0, q = 0;
        StringBuffer out = new StringBuffer();

        if (isRootLabel(input)) {
            return ".";
        }

        while (p < input.length()) {
            q = searchDots(input, p);
            out.append(toASCIIInternal(input.substring(p, q),  flag));
            if (q != (input.length())) {
               // has more labels, or keep the trailing dot as at present
               out.append('.');
            }
            p = q + 1;
        }

        return out.toString();
    }

    //
    // toASCII operation; should only apply to a single label
    //
    private static String toASCIIInternal(String label, int flag)
    {
        // step 1
        // Check if the string contains code points outside the ASCII range 0..0x7c.
        boolean isASCII  = isAllASCII(label);
        StringBuffer dest;

        // step 2
        // perform the nameprep operation; flag ALLOW_UNASSIGNED is used here
        if (!isASCII) {
            UCharacterIterator iter = UCharacterIterator.getInstance(label);
            try {
                dest = namePrep.prepare(iter, flag);
            } catch (java.text.ParseException e) {
                throw new IllegalArgumentException(e);
            }
        } else {
            dest = new StringBuffer(label);
        }

        // step 8, move forward to check the smallest number of the code points
        // the length must be inside 1..63
        if (dest.length() == 0) {
            throw new IllegalArgumentException(
                        "Empty label is not a legal name");
        }

        // step 3
        // Verify the absence of non-LDH ASCII code points
        //   0..0x2c, 0x2e..0x2f, 0x3a..0x40, 0x5b..0x60, 0x7b..0x7f
        // Verify the absence of leading and trailing hyphen
        boolean useSTD3ASCIIRules = ((flag & IDN.USE_STD3_ASCII_RULES) != 0);
        if (useSTD3ASCIIRules) {
            for (int i = 0; i < dest.length(); i++) {
                int c = dest.charAt(i);
                if (isNonLDHAsciiCodePoint(c)) {
                    throw new IllegalArgumentException(
                        "Contains non-LDH ASCII characters");
                }
            }

            if (dest.charAt(0) == '-' ||
                dest.charAt(dest.length() - 1) == '-') {

                throw new IllegalArgumentException(
                        "Has leading or trailing hyphen");
            }
        }

        if (!isASCII) {
            // step 4
            // If all code points are inside 0..0x7f, skip to step 8
            if (!isAllASCII(dest.toString())) {
                // step 5
                // verify the sequence does not begin with ACE prefix
                if(!startsWithACEPrefix(dest)){

                    // step 6
                    // encode the sequence with punycode
                    try {
                        dest = Punycode.encode(dest, null);
                    } catch (java.text.ParseException e) {
                        throw new IllegalArgumentException(e);
                    }

                    dest = toASCIILower(dest);

                    // step 7
                    // prepend the ACE prefix
                    dest.insert(0, ACE_PREFIX);
                } else {
                    throw new IllegalArgumentException("The input starts with the ACE Prefix");
                }

            }
        }

        // step 8
        // the length must be inside 1..63
        if (dest.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("The label in the input is too long");
        }

        return dest.toString();
    }

    //
    // LDH stands for "letter/digit/hyphen", with characters restricted to the
    // 26-letter Latin alphabet <A-Z a-z>, the digits <0-9>, and the hyphen
    // <->.
    // Non LDH refers to characters in the ASCII range, but which are not
    // letters, digits or the hypen.
    //
    // non-LDH = 0..0x2C, 0x2E..0x2F, 0x3A..0x40, 0x5B..0x60, 0x7B..0x7F
    //
    private static boolean isNonLDHAsciiCodePoint(int ch){
        return (0x0000 <= ch && ch <= 0x002C) ||
               (0x002E <= ch && ch <= 0x002F) ||
               (0x003A <= ch && ch <= 0x0040) ||
               (0x005B <= ch && ch <= 0x0060) ||
               (0x007B <= ch && ch <= 0x007F);
    }

    //
    // search dots in a string and return the index of that character;
    // or if there is no dots, return the length of input string
    // dots might be: \u002E (full stop), \u3002 (ideographic full stop), \uFF0E (fullwidth full stop),
    // and \uFF61 (halfwidth ideographic full stop).
    //
    private static int searchDots(String s, int start) {
        int i;
        for (i = start; i < s.length(); i++) {
            if (isLabelSeparator(s.charAt(i))) {
                break;
            }
        }

        return i;
    }

    //
    // to check if a string is a root label, ".".
    //
    private static boolean isRootLabel(String s) {
        return (s.length() == 1 && isLabelSeparator(s.charAt(0)));
    }

    //
    // to check if a character is a label separator, i.e. a dot character.
    //
    private static boolean isLabelSeparator(char c) {
        return (c == '.' || c == '\u3002' || c == '\uFF0E' || c == '\uFF61');
    }

    //
    // to check if a string starts with ACE-prefix
    //
    private static boolean startsWithACEPrefix(StringBuffer input){
        boolean startsWithPrefix = true;

        if(input.length() < ACE_PREFIX_LENGTH){
            return false;
        }
        for(int i = 0; i < ACE_PREFIX_LENGTH; i++){
            if(toASCIILower(input.charAt(i)) != ACE_PREFIX.charAt(i)){
                startsWithPrefix = false;
            }
        }
        return startsWithPrefix;
    }

    private static char toASCIILower(char ch){
        if('A' <= ch && ch <= 'Z'){
            return (char)(ch + 'a' - 'A');
        }
        return ch;
    }

    private static StringBuffer toASCIILower(StringBuffer input){
        StringBuffer dest = new StringBuffer();
        for(int i = 0; i < input.length();i++){
            dest.append(toASCIILower(input.charAt(i)));
        }
        return dest;
    }

    //
    // to check if a string only contains US-ASCII code point
    //
    private static boolean isAllASCII(String input) {
        boolean isASCII = true;
        for (int i = 0; i < input.length(); i++) {
            int c = input.charAt(i);
            if (c > 0x7F) {
                isASCII = false;
                break;
            }
        }
        return isASCII;
    }

}
