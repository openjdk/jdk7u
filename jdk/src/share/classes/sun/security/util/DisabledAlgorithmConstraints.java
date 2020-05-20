/*
 * Copyright (c) 2010, 2017, Oracle and/or its affiliates. All rights reserved.
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

import sun.misc.JavaUtilCalendarAccess;
import sun.misc.SharedSecrets;
import sun.security.validator.Validator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.CryptoPrimitive;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertPathValidatorException.BasicReason;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import static java.util.Calendar.FIELD_COUNT;

/**
 * Algorithm constraints for disabled algorithms property
 *
 * See the "jdk.certpath.disabledAlgorithms" specification in java.security
 * for the syntax of the disabled algorithm string.
 */
public class DisabledAlgorithmConstraints extends AbstractAlgorithmConstraints {
    private static final Debug debug = Debug.getInstance("certpath");

    // the known security property, jdk.certpath.disabledAlgorithms
    public static final String PROPERTY_CERTPATH_DISABLED_ALGS =
            "jdk.certpath.disabledAlgorithms";

    // the known security property, jdk.tls.disabledAlgorithms
    public static final String PROPERTY_TLS_DISABLED_ALGS =
            "jdk.tls.disabledAlgorithms";

    // the known security property, jdk.jar.disabledAlgorithms
    public static final String PROPERTY_JAR_DISABLED_ALGS =
            "jdk.jar.disabledAlgorithms";

    private final String[] disabledAlgorithms;
    private final Constraints algorithmConstraints;

    /**
     * Initialize algorithm constraints with the specified security property.
     *
     * @param propertyName the security property name that define the disabled
     *        algorithm constraints
     */
    public DisabledAlgorithmConstraints(String propertyName) {
        this(propertyName, new AlgorithmDecomposer());
    }

    /**
     * Initialize algorithm constraints with the specified security property
     * for a specific usage type.
     *
     * @param propertyName the security property name that define the disabled
     *        algorithm constraints
     * @param decomposer an alternate AlgorithmDecomposer.
     */
    public DisabledAlgorithmConstraints(String propertyName,
            AlgorithmDecomposer decomposer) {
        super(decomposer);
        disabledAlgorithms = getAlgorithms(propertyName);
        algorithmConstraints = new Constraints(disabledAlgorithms);
    }

    /*
     * This only checks if the algorithm has been completely disabled.  If
     * there are keysize or other limit, this method allow the algorithm.
     */
    @Override
    public final boolean permits(Set<CryptoPrimitive> primitives,
            String algorithm, AlgorithmParameters parameters) {
        if (!checkAlgorithm(disabledAlgorithms, algorithm, decomposer)) {
            return false;
        }

        if (parameters != null) {
            return algorithmConstraints.permits(algorithm, parameters);
        }

        return true;
    }

    /*
     * Checks if the key algorithm has been disabled or constraints have been
     * placed on the key.
     */
    @Override
    public final boolean permits(Set<CryptoPrimitive> primitives, Key key) {
        return checkConstraints(primitives, "", key, null);
    }

    /*
     * Checks if the key algorithm has been disabled or if constraints have
     * been placed on the key.
     */
    @Override
    public final boolean permits(Set<CryptoPrimitive> primitives,
            String algorithm, Key key, AlgorithmParameters parameters) {

        if (algorithm == null || algorithm.length() == 0) {
            throw new IllegalArgumentException("No algorithm name specified");
        }

        return checkConstraints(primitives, algorithm, key, parameters);
    }

    public final void permits(ConstraintsParameters cp)
            throws CertPathValidatorException {
        permits(cp.getAlgorithm(), cp);
    }

    public final void permits(String algorithm, Key key,
            AlgorithmParameters params, String variant)
            throws CertPathValidatorException {
        permits(algorithm, new ConstraintsParameters(algorithm, params, key,
                (variant == null) ? Validator.VAR_GENERIC : variant));
    }

    /*
     * Check if a x509Certificate object is permitted.  Check if all
     * algorithms are allowed, certificate constraints, and the
     * public key against key constraints.
     *
     * Uses new style permit() which throws exceptions.
     */

    public final void permits(String algorithm, ConstraintsParameters cp)
            throws CertPathValidatorException {
        algorithmConstraints.permits(algorithm, cp);
    }

    // Check if a string is contained inside the property
    public boolean checkProperty(String param) {
        param = param.toLowerCase(Locale.ENGLISH);
        for (String block : disabledAlgorithms) {
            if (block.toLowerCase(Locale.ENGLISH).indexOf(param) >= 0) {
                return true;
            }
        }
        return false;
    }

    // Check algorithm constraints with key and algorithm
    private boolean checkConstraints(Set<CryptoPrimitive> primitives,
            String algorithm, Key key, AlgorithmParameters parameters) {

        // check the key parameter, it cannot be null.
        if (key == null) {
            throw new IllegalArgumentException("The key cannot be null");
        }

        // check the signature algorithm with parameters
        if (algorithm != null && algorithm.length() != 0) {
            if (!permits(primitives, algorithm, parameters)) {
                return false;
            }
        }

        // check the key algorithm
        if (!permits(primitives, key.getAlgorithm(), null)) {
            return false;
        }

        // check the key constraints
        return algorithmConstraints.permits(key);
    }


    /**
     * Key and Certificate Constraints
     *
     * The complete disabling of an algorithm is not handled by Constraints or
     * Constraint classes.  That is addressed with
     *   permit(Set<CryptoPrimitive>, String, AlgorithmParameters)
     *
     * When passing a Key to permit(), the boolean return values follow the
     * same as the interface class AlgorithmConstraints.permit().  This is to
     * maintain compatibility:
     * 'true' means the operation is allowed.
     * 'false' means it failed the constraints and is disallowed.
     *
     * When passing ConstraintsParameters through permit(), an exception
     * will be thrown on a failure to better identify why the operation was
     * disallowed.
     */

    private static class Constraints {
        private Map<String, List<Constraint>> constraintsMap = new HashMap<>();

        private static class Holder {
            private static final Pattern DENY_AFTER_PATTERN = Pattern.compile(
                    "denyAfter\\s+(\\d{4})-(\\d{2})-(\\d{2})");
        }

        public Constraints(String[] constraintArray) {
            for (String constraintEntry : constraintArray) {
                if (constraintEntry == null || constraintEntry.isEmpty()) {
                    continue;
                }

                constraintEntry = constraintEntry.trim();
                if (debug != null) {
                    debug.println("Constraints: " + constraintEntry);
                }

                // Check if constraint is a complete disabling of an
                // algorithm or has conditions.
                int space = constraintEntry.indexOf(' ');
                String algorithm = AlgorithmDecomposer.hashName(
                        ((space > 0 ? constraintEntry.substring(0, space) :
                                constraintEntry)));

                List<Constraint> constraintList = constraintsMap.get(
                    algorithm.toUpperCase(Locale.ENGLISH));
                if (constraintList == null) {
                    constraintList = new ArrayList<>(1);
                }

                // Consider the impact of algorithm aliases.
                for (String alias : AlgorithmDecomposer.getAliases(algorithm)) {
                    List<Constraint> aliasList = constraintsMap.get(alias);
                    if (aliasList == null) {
                        constraintsMap.put(
                            alias.toUpperCase(Locale.ENGLISH), constraintList);
                    }
                }
                if (space <= 0) {
                    constraintList.add(new DisabledConstraint(algorithm));
                    continue;
                }

                String policy = constraintEntry.substring(space + 1);

                // Convert constraint conditions into Constraint classes
                Constraint c, lastConstraint = null;
                // Allow only one jdkCA entry per constraint entry
                boolean jdkCALimit = false;
                // Allow only one denyAfter entry per constraint entry
                boolean denyAfterLimit = false;

                for (String entry : policy.split("&")) {
                    entry = entry.trim();

                    Matcher matcher;
                    if (entry.startsWith("keySize")) {
                        if (debug != null) {
                            debug.println("Constraints set to keySize: " +
                                    entry);
                        }
                        StringTokenizer tokens = new StringTokenizer(entry);
                        if (!"keySize".equals(tokens.nextToken())) {
                            throw new IllegalArgumentException("Error in " +
                                    "security property. Constraint unknown: " +
                                    entry);
                        }
                        c = new KeySizeConstraint(algorithm,
                                KeySizeConstraint.Operator.of(tokens.nextToken()),
                                Integer.parseInt(tokens.nextToken()));

                    } else if (entry.equalsIgnoreCase("jdkCA")) {
                        if (debug != null) {
                            debug.println("Constraints set to jdkCA.");
                        }
                        if (jdkCALimit) {
                            throw new IllegalArgumentException("Only one " +
                                    "jdkCA entry allowed in property. " +
                                    "Constraint: " + constraintEntry);
                        }
                        c = new jdkCAConstraint(algorithm);
                        jdkCALimit = true;

                    } else if (entry.startsWith("denyAfter") &&
                            (matcher = Holder.DENY_AFTER_PATTERN.matcher(entry))
                                    .matches()) {
                        if (debug != null) {
                            debug.println("Constraints set to denyAfter");
                        }
                        if (denyAfterLimit) {
                            throw new IllegalArgumentException("Only one " +
                                    "denyAfter entry allowed in property. " +
                                    "Constraint: " + constraintEntry);
                        }
                        int year = Integer.parseInt(matcher.group(1));
                        int month = Integer.parseInt(matcher.group(2));
                        int day = Integer.parseInt(matcher.group(3));
                        c = new DenyAfterConstraint(algorithm, year, month,
                                day);
                        denyAfterLimit = true;
                    } else if (entry.startsWith("usage")) {
                        String s[] = (entry.substring(5)).trim().split(" ");
                        c = new UsageConstraint(algorithm, s);
                        if (debug != null) {
                            debug.println("Constraints usage length is " + s.length);
                        }
                    } else {
                        throw new IllegalArgumentException("Error in security" +
                                " property. Constraint unknown: " + entry);
                    }

                    // Link multiple conditions for a single constraint
                    // into a linked list.
                    if (lastConstraint == null) {
                        constraintList.add(c);
                    } else {
                        lastConstraint.nextConstraint = c;
                    }
                    lastConstraint = c;
                }
            }
        }

        // Get applicable constraints based off the signature algorithm
        private List<Constraint> getConstraints(String algorithm) {
            return constraintsMap.get(algorithm.toUpperCase(Locale.ENGLISH));
        }

        // Check if KeySizeConstraints permit the specified key
        public boolean permits(Key key) {
            List<Constraint> list = getConstraints(key.getAlgorithm());
            if (list == null) {
                return true;
            }
            for (Constraint constraint : list) {
                if (!constraint.permits(key)) {
                    if (debug != null) {
                        debug.println("keySizeConstraint: failed key " +
                                "constraint check " + KeyUtil.getKeySize(key));
                    }
                    return false;
                }
            }
            return true;
        }

        // Check if constraints permit this AlgorithmParameters.
        public boolean permits(String algorithm, AlgorithmParameters aps) {
            List<Constraint> list = getConstraints(algorithm);
            if (list == null) {
                return true;
            }

            for (Constraint constraint : list) {
                if (!constraint.permits(aps)) {
                    if (debug != null) {
                        debug.println("keySizeConstraint: failed algorithm " +
                                "parameters constraint check " + aps);
                    }

                    return false;
                }
            }

            return true;
        }

        // Check if constraints permit this cert.
        public void permits(String algorithm, ConstraintsParameters cp)
                throws CertPathValidatorException {
            X509Certificate cert = cp.getCertificate();

            if (debug != null) {
                debug.println("Constraints.permits(): " + algorithm +
                        " Variant: " + cp.getVariant());
            }

            // Get all signature algorithms to check for constraints
            Set<String> algorithms = new HashSet<>();
            if (algorithm != null) {
                algorithms.addAll(AlgorithmDecomposer.decomposeOneHash(algorithm));
                algorithms.add(algorithm);
            }

            // Attempt to add the public key algorithm if cert provided
            if (cert != null) {
                algorithms.add(cert.getPublicKey().getAlgorithm());
            }
            if (cp.getPublicKey() != null) {
                algorithms.add(cp.getPublicKey().getAlgorithm());
            }
            // Check all applicable constraints
            for (String alg : algorithms) {
                List<Constraint> list = getConstraints(alg);
                if (list == null) {
                    continue;
                }
                for (Constraint constraint : list) {
                    constraint.permits(cp);
                }
            }
        }
    }

    /**
     * This abstract Constraint class for algorithm-based checking
     * may contain one or more constraints.  If the '&' on the {@Security}
     * property is used, multiple constraints have been grouped together
     * requiring all the constraints to fail for the check to be disallowed.
     *
     * If the class contains multiple constraints, the next constraint
     * is stored in {@code nextConstraint} in linked-list fashion.
     */
    private abstract static class Constraint {
        String algorithm;
        Constraint nextConstraint = null;

        // operator
        enum Operator {
            EQ,         // "=="
            NE,         // "!="
            LT,         // "<"
            LE,         // "<="
            GT,         // ">"
            GE;         // ">="

            static Operator of(String s) {
                switch (s) {
                    case "==":
                        return EQ;
                    case "!=":
                        return NE;
                    case "<":
                        return LT;
                    case "<=":
                        return LE;
                    case ">":
                        return GT;
                    case ">=":
                        return GE;
                }

                throw new IllegalArgumentException("Error in security " +
                        "property. " + s + " is not a legal Operator");
            }
        }

        /**
         * Check if an algorithm constraint is permitted with a given key.
         *
         * If the check inside of {@code permit()} fails, it must call
         * {@code next()} with the same {@code Key} parameter passed if
         * multiple constraints need to be checked.
         *
         * @param key Public key
         * @return 'true' if constraint is allowed, 'false' if disallowed.
         */
        public boolean permits(Key key) {
            return true;
        }

        /**
         * Check if the algorithm constraint permits a given cryptographic
         * parameters.
         *
         * @param parameters the cryptographic parameters
         * @return 'true' if the cryptographic parameters is allowed,
         *         'false' ortherwise.
         */
        public boolean permits(AlgorithmParameters parameters) {
            return true;
        }

        /**
         * Check if an algorithm constraint is permitted with a given
         * ConstraintsParameters.
         *
         * If the check inside of {@code permits()} fails, it must call
         * {@code next()} with the same {@code ConstraintsParameters}
         * parameter passed if multiple constraints need to be checked.
         *
         * @param cp CertConstraintParameter containing certificate info
         * @throws CertPathValidatorException if constraint disallows.
         *
         */
        public abstract void permits(ConstraintsParameters cp)
                throws CertPathValidatorException;

        /**
         * Recursively check if the constraints are allowed.
         *
         * If {@code nextConstraint} is non-null, this method will
         * call {@code nextConstraint}'s {@code permits()} to check if the
         * constraint is allowed or denied.  If the constraint's
         * {@code permits()} is allowed, this method will exit this and any
         * recursive next() calls, returning 'true'.  If the constraints called
         * were disallowed, the last constraint will throw
         * {@code CertPathValidatorException}.
         *
         * @param cp ConstraintsParameters
         * @return 'true' if constraint allows the operation, 'false' if
         * we are at the end of the constraint list or,
         * {@code nextConstraint} is null.
         */
        boolean next(ConstraintsParameters cp)
                throws CertPathValidatorException {
            if (nextConstraint != null) {
                nextConstraint.permits(cp);
                return true;
            }
            return false;
        }

        /**
         * Recursively check if this constraint is allowed,
         *
         * If {@code nextConstraint} is non-null, this method will
         * call {@code nextConstraint}'s {@code permit()} to check if the
         * constraint is allowed or denied.  If the constraint's
         * {@code permit()} is allowed, this method will exit this and any
         * recursive next() calls, returning 'true'.  If the constraints
         * called were disallowed the check will exit with 'false'.
         *
         * @param key Public key
         * @return 'true' if constraint allows the operation, 'false' if
         * the constraint denies the operation.
         */
        boolean next(Key key) {
            if (nextConstraint != null && nextConstraint.permits(key)) {
                return true;
            }
            return false;
        }

        String extendedMsg(ConstraintsParameters cp) {
            return (cp.getCertificate() == null ? "." :
                    " used with certificate: " +
                            cp.getCertificate().getSubjectX500Principal() +
                    (cp.getVariant() != Validator.VAR_GENERIC ?
                            ".  Usage was " + cp.getVariant() : "."));
        }
    }

    /*
     * This class contains constraints dealing with the certificate chain
     * of the certificate.
     */
    private static class jdkCAConstraint extends Constraint {
        jdkCAConstraint(String algo) {
            algorithm = algo;
        }

        /*
         * Check if ConstraintsParameters has a trusted match, if it does
         * call next() for any following constraints. If it does not, exit
         * as this constraint(s) does not restrict the operation.
         */
        @Override
        public void permits(ConstraintsParameters cp)
                throws CertPathValidatorException {
            if (debug != null) {
                debug.println("jdkCAConstraints.permits(): " + algorithm);
            }

            // Check chain has a trust anchor in cacerts
            if (cp.isTrustedMatch()) {
                if (next(cp)) {
                    return;
                }
                throw new CertPathValidatorException(
                        "Algorithm constraints check failed on certificate " +
                        "anchor limits. " + algorithm + extendedMsg(cp),
                        null, null, -1, BasicReason.ALGORITHM_CONSTRAINED);
            }
        }
    }

    /*
     * This class handles the denyAfter constraint.  The date is in the UTC/GMT
     * timezone.
     */
    private static class DenyAfterConstraint extends Constraint {
        private Date denyAfterDate;
        private static final SimpleDateFormat dateFormat =
                new SimpleDateFormat("EEE, MMM d HH:mm:ss z yyyy");

        DenyAfterConstraint(String algo, int year, int month, int day) {
            Calendar c;

            algorithm = algo;

            if (debug != null) {
                debug.println("DenyAfterConstraint read in as:  year " +
                        year + ", month = " + month + ", day = " + day);
            }

            c = new CalendarBuilder().setTimeZone(TimeZone.getTimeZone("GMT"))
                    .setDate(year, month - 1, day).build();

            if (year > c.getActualMaximum(Calendar.YEAR) ||
                    year < c.getActualMinimum(Calendar.YEAR)) {
                throw new IllegalArgumentException(
                        "Invalid year given in constraint: " + year);
            }
            if ((month - 1) > c.getActualMaximum(Calendar.MONTH) ||
                    (month - 1) < c.getActualMinimum(Calendar.MONTH)) {
                throw new IllegalArgumentException(
                        "Invalid month given in constraint: " + month);
            }
            if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH) ||
                    day < c.getActualMinimum(Calendar.DAY_OF_MONTH)) {
                throw new IllegalArgumentException(
                        "Invalid Day of Month given in constraint: " + day);
            }

            denyAfterDate = c.getTime();
            if (debug != null) {
                debug.println("DenyAfterConstraint date set to: " +
                        dateFormat.format(denyAfterDate));
            }
        }

        /*
         * Checking that the provided date is not beyond the constraint date.
         * The provided date can be the PKIXParameter date if given,
         * otherwise it is the current date.
         *
         * If the constraint disallows, call next() for any following
         * constraints. Throw an exception if this is the last constraint.
         */
        @Override
        public void permits(ConstraintsParameters cp)
                throws CertPathValidatorException {
            Date currentDate;
            String errmsg;

            if (cp.getJARTimestamp() != null) {
                currentDate = cp.getJARTimestamp().getTimestamp();
                errmsg = "JAR Timestamp date: ";
            } else if (cp.getPKIXParamDate() != null) {
                currentDate = cp.getPKIXParamDate();
                errmsg = "PKIXParameter date: ";
            } else {
                currentDate = new Date();
                errmsg = "Current date: ";
            }

            if (!denyAfterDate.after(currentDate)) {
                if (next(cp)) {
                    return;
                }
                throw new CertPathValidatorException(
                        "denyAfter constraint check failed: " + algorithm +
                        " used with Constraint date: " +
                        dateFormat.format(denyAfterDate) + "; " + errmsg +
                        dateFormat.format(currentDate) + extendedMsg(cp),
                        null, null, -1, BasicReason.ALGORITHM_CONSTRAINED);
            }
        }

        /*
         * Return result if the constraint's date is beyond the current date
         * in UTC timezone.
         */
        @Override
        public boolean permits(Key key) {
            if (next(key)) {
                return true;
            }
            if (debug != null) {
                debug.println("DenyAfterConstraints.permits(): " + algorithm);
            }

            return denyAfterDate.after(new Date());
        }
    }

    /*
     * The usage constraint is for the "usage" keyword.  It checks against the
     * variant value in ConstraintsParameters.
     */
    private static class UsageConstraint extends Constraint {
        String[] usages;

        UsageConstraint(String algorithm, String[] usages) {
            this.algorithm = algorithm;
            this.usages = usages;
        }

        @Override
        public void permits(ConstraintsParameters cp)
                throws CertPathValidatorException {
            for (String usage : usages) {

                String v = null;
                if (usage.compareToIgnoreCase("TLSServer") == 0) {
                    v = Validator.VAR_TLS_SERVER;
                } else if (usage.compareToIgnoreCase("TLSClient") == 0) {
                    v = Validator.VAR_TLS_CLIENT;
                } else if (usage.compareToIgnoreCase("SignedJAR") == 0) {
                    v = Validator.VAR_PLUGIN_CODE_SIGNING;
                }

                if (debug != null) {
                    debug.println("Checking if usage constraint \"" + v +
                            "\" matches \"" + cp.getVariant() + "\"");
                    // Because usage checking can come from many places
                    // a stack trace is very helpful.
                    ByteArrayOutputStream ba = new ByteArrayOutputStream();
                    PrintStream ps = new PrintStream(ba);
                    (new Exception()).printStackTrace(ps);
                    debug.println(ba.toString());
                }
                if (cp.getVariant().compareTo(v) == 0) {
                    if (next(cp)) {
                        return;
                    }
                    throw new CertPathValidatorException("Usage constraint " +
                            usage + " check failed: " + algorithm +
                            extendedMsg(cp),
                            null, null, -1, BasicReason.ALGORITHM_CONSTRAINED);
                }
            }
        }
    }

    /*
     * This class contains constraints dealing with the key size
     * support limits per algorithm.   e.g.  "keySize <= 1024"
     */
    private static class KeySizeConstraint extends Constraint {

        private int minSize;            // the minimal available key size
        private int maxSize;            // the maximal available key size
        private int prohibitedSize = -1;    // unavailable key sizes

        public KeySizeConstraint(String algo, Operator operator, int length) {
            algorithm = algo;
            switch (operator) {
                case EQ:      // an unavailable key size
                    this.minSize = 0;
                    this.maxSize = Integer.MAX_VALUE;
                    prohibitedSize = length;
                    break;
                case NE:
                    this.minSize = length;
                    this.maxSize = length;
                    break;
                case LT:
                    this.minSize = length;
                    this.maxSize = Integer.MAX_VALUE;
                    break;
                case LE:
                    this.minSize = length + 1;
                    this.maxSize = Integer.MAX_VALUE;
                    break;
                case GT:
                    this.minSize = 0;
                    this.maxSize = length;
                    break;
                case GE:
                    this.minSize = 0;
                    this.maxSize = length > 1 ? (length - 1) : 0;
                    break;
                default:
                    // unlikely to happen
                    this.minSize = Integer.MAX_VALUE;
                    this.maxSize = -1;
            }
        }

        /*
         * If we are passed a certificate, extract the public key and use it.
         *
         * Check if each constraint fails and check if there is a linked
         * constraint  Any permitted constraint will exit the linked list
         * to allow the operation.
         */
        @Override
        public void permits(ConstraintsParameters cp)
                throws CertPathValidatorException {
            Key key = null;
            if (cp.getPublicKey() != null) {
                key = cp.getPublicKey();
            } else if (cp.getCertificate() != null) {
                key = cp.getCertificate().getPublicKey();
            }
            if (key != null && !permitsImpl(key)) {
                if (nextConstraint != null) {
                    nextConstraint.permits(cp);
                    return;
                }
                throw new CertPathValidatorException(
                        "Algorithm constraints check failed on keysize limits. " +
                        algorithm + " " + KeyUtil.getKeySize(key) + "bit key" +
                        extendedMsg(cp),
                        null, null, -1, BasicReason.ALGORITHM_CONSTRAINED);
            }
        }


        // Check if key constraint disable the specified key
        // Uses old style permit()
        @Override
        public boolean permits(Key key) {
            // If we recursively find a constraint that permits us to use
            // this key, return true and skip any other constraint checks.
            if (nextConstraint != null && nextConstraint.permits(key)) {
                return true;
            }
            if (debug != null) {
                debug.println("KeySizeConstraints.permits(): " + algorithm);
            }

            return permitsImpl(key);
        }

        @Override
        public boolean permits(AlgorithmParameters parameters) {
            String paramAlg = parameters.getAlgorithm();
            if (!algorithm.equalsIgnoreCase(parameters.getAlgorithm())) {
                // Consider the impact of the algorithm aliases.
                Collection<String> aliases =
                        AlgorithmDecomposer.getAliases(algorithm);
                if (!aliases.contains(paramAlg)) {
                    return true;
                }
            }

            int keySize = KeyUtil.getKeySize(parameters);
            if (keySize == 0) {
                return false;
            } else if (keySize > 0) {
                return !((keySize < minSize) || (keySize > maxSize) ||
                    (prohibitedSize == keySize));
            }   // Otherwise, the key size is not accessible or determined.
                // Conservatively, please don't disable such keys.

            return true;
        }

        private boolean permitsImpl(Key key) {
            // Verify this constraint is for this public key algorithm
            if (algorithm.compareToIgnoreCase(key.getAlgorithm()) != 0) {
                return true;
            }

            int size = KeyUtil.getKeySize(key);
            if (size == 0) {
                return false;    // we don't allow any key of size 0.
            } else if (size > 0) {
                return !((size < minSize) || (size > maxSize) ||
                    (prohibitedSize == size));
            }   // Otherwise, the key size is not accessible. Conservatively,
                // please don't disable such keys.

            return true;
        }
    }

    /*
     * This constraint is used for the complete disabling of the algorithm.
     */
    private static class DisabledConstraint extends Constraint {
        DisabledConstraint(String algo) {
            algorithm = algo;
        }

        @Override
        public void permits(ConstraintsParameters cp)
                throws CertPathValidatorException {
            throw new CertPathValidatorException(
                    "Algorithm constraints check failed on disabled " +
                            "algorithm: " + algorithm + extendedMsg(cp),
                    null, null, -1, BasicReason.ALGORITHM_CONSTRAINED);
        }

        @Override
        public boolean permits(Key key) {
            return false;
        }
    }

    /**
     * {@code Calendar.Builder} is used for creating a {@code Calendar} from
     * various date-time parameters.
     *
     * <p>There are two ways to set a {@code Calendar} to a date-time value. One
     * is to set the instant parameter to a millisecond offset from the <a
     * href="Calendar.html#Epoch">Epoch</a>. The other is to set individual
     * field parameters, such as {@link Calendar#YEAR YEAR}, to their desired
     * values. These two ways can't be mixed. Trying to set both the instant and
     * individual fields will cause an {@link IllegalStateException} to be
     * thrown. However, it is permitted to override previous values of the
     * instant or field parameters.
     *
     * <p>If no enough field parameters are given for determining date and/or
     * time, calendar specific default values are used when building a
     * {@code Calendar}. For example, if the {@link Calendar#YEAR YEAR} value
     * isn't given for the Gregorian calendar, 1970 will be used. If there are
     * any conflicts among field parameters, the <a
     * href="Calendar.html#resolution"> resolution rules</a> are applied.
     * Therefore, the order of field setting matters.
     *
     * <p>In addition to the date-time parameters,
     * the {@linkplain #setLocale(Locale) locale},
     * {@linkplain #setTimeZone(TimeZone) time zone},
     * {@linkplain #setWeekDefinition(int, int) week definition}, and
     * {@linkplain #setLenient(boolean) leniency mode} parameters can be set.
     *
     * <p><b>Examples</b>
     * <p>The following are sample usages. Sample code assumes that the
     * {@code Calendar} constants are statically imported.
     *
     * <p>The following code produces a {@code Calendar} with date 2012-12-31
     * (Gregorian) because Monday is the first day of a week with the <a
     * href="GregorianCalendar.html#iso8601_compatible_setting"> ISO 8601
     * compatible week parameters</a>.
     * <pre>
     *   Calendar cal = new Calendar.Builder().setCalendarType("iso8601")
     *                        .setWeekDate(2013, 1, MONDAY).build();</pre>
     * <p>The following code produces a Japanese {@code Calendar} with date
     * 1989-01-08 (Gregorian), assuming that the default {@link Calendar#ERA ERA}
     * is <em>Heisei</em> that started on that day.
     * <pre>
     *   Calendar cal = new Calendar.Builder().setCalendarType("japanese")
     *                        .setFields(YEAR, 1, DAY_OF_YEAR, 1).build();</pre>
     *
     * @since 1.8
     * @see Calendar#getInstance(TimeZone, Locale)
     * @see Calendar#fields
     */
    private static class CalendarBuilder {
        private static final int NFIELDS = FIELD_COUNT + 1; // +1 for WEEK_YEAR
        private static final int WEEK_YEAR = FIELD_COUNT;

        /**
         * The corresponding fields[] has no value.
         */
        private static final int        UNSET = 0;

        /**
         * The value of the corresponding fields[] has been calculated internally.
         */
        private static final int        COMPUTED = 1;

        /**
         * The value of the corresponding fields[] has been set externally. Stamp
         * values which are greater than 1 represents the (pseudo) time when the
         * corresponding fields[] value was set.
         */
        private static final int        MINIMUM_USER_STAMP = 2;

        private long instant;
        // Calendar.stamp[] (lower half) and Calendar.fields[] (upper half) combined
        private int[] fields;
        // Pseudo timestamp starting from MINIMUM_USER_STAMP.
        // (COMPUTED is used to indicate that the instant has been set.)
        private int nextStamp;
        // maxFieldIndex keeps the max index of fields which have been set.
        // (WEEK_YEAR is never included.)
        private int maxFieldIndex;
        private String type;
        private TimeZone zone;
        private boolean lenient = true;
        private Locale locale;
        private int firstDayOfWeek, minimalDaysInFirstWeek;

        /**
         * Constructs a {@code Calendar.Builder}.
         */
        public CalendarBuilder() {
        }

        /**
         * Sets the instant parameter to the given {@code instant} value that is
         * a millisecond offset from <a href="Calendar.html#Epoch">the
         * Epoch</a>.
         *
         * @param instant a millisecond offset from the Epoch
         * @return this {@code Calendar.Builder}
         * @throws IllegalStateException if any of the field parameters have
         *                               already been set
         * @see Calendar#setTime(Date)
         * @see Calendar#setTimeInMillis(long)
         * @see Calendar#time
         */
        public CalendarBuilder setInstant(long instant) {
            if (fields != null) {
                throw new IllegalStateException();
            }
            this.instant = instant;
            nextStamp = COMPUTED;
            return this;
        }

        /**
         * Sets the instant parameter to the {@code instant} value given by a
         * {@link Date}. This method is equivalent to a call to
         * {@link #setInstant(long) setInstant(instant.getTime())}.
         *
         * @param instant a {@code Date} representing a millisecond offset from
         *                the Epoch
         * @return this {@code Calendar.Builder}
         * @throws NullPointerException  if {@code instant} is {@code null}
         * @throws IllegalStateException if any of the field parameters have
         *                               already been set
         * @see Calendar#setTime(Date)
         * @see Calendar#setTimeInMillis(long)
         * @see Calendar#time
         */
        public CalendarBuilder setInstant(Date instant) {
            return setInstant(instant.getTime()); // NPE if instant == null
        }

        /**
         * Sets the {@code field} parameter to the given {@code value}.
         * {@code field} is an index to the {@link Calendar#fields}, such as
         * {@link Calendar#DAY_OF_MONTH DAY_OF_MONTH}. Field value validation is
         * not performed in this method. Any out of range values are either
         * normalized in lenient mode or detected as an invalid value in
         * non-lenient mode when building a {@code Calendar}.
         *
         * @param field an index to the {@code Calendar} fields
         * @param value the field value
         * @return this {@code Calendar.Builder}
         * @throws IllegalArgumentException if {@code field} is invalid
         * @throws IllegalStateException if the instant value has already been set,
         *                      or if fields have been set too many
         *                      (approximately {@link Integer#MAX_VALUE}) times.
         * @see Calendar#set(int, int)
         */
        public CalendarBuilder set(int field, int value) {
            // Note: WEEK_YEAR can't be set with this method.
            if (field < 0 || field >= FIELD_COUNT) {
                throw new IllegalArgumentException("field is invalid");
            }
            if (isInstantSet()) {
                throw new IllegalStateException("instant has been set");
            }
            allocateFields();
            internalSet(field, value);
            return this;
        }

        /**
         * Sets field parameters to their values given by
         * {@code fieldValuePairs} that are pairs of a field and its value.
         * For example,
         * <pre>
         *   setFeilds(Calendar.YEAR, 2013,
         *             Calendar.MONTH, Calendar.DECEMBER,
         *             Calendar.DAY_OF_MONTH, 23);</pre>
         * is equivalent to the sequence of the following
         * {@link #set(int, int) set} calls:
         * <pre>
         *   set(Calendar.YEAR, 2013)
         *   .set(Calendar.MONTH, Calendar.DECEMBER)
         *   .set(Calendar.DAY_OF_MONTH, 23);</pre>
         *
         * @param fieldValuePairs field-value pairs
         * @return this {@code Calendar.Builder}
         * @throws NullPointerException if {@code fieldValuePairs} is {@code null}
         * @throws IllegalArgumentException if any of fields are invalid,
         *             or if {@code fieldValuePairs.length} is an odd number.
         * @throws IllegalStateException    if the instant value has been set,
         *             or if fields have been set too many (approximately
         *             {@link Integer#MAX_VALUE}) times.
         */
        public CalendarBuilder setFields(int... fieldValuePairs) {
            int len = fieldValuePairs.length;
            if ((len % 2) != 0) {
                throw new IllegalArgumentException();
            }
            if (isInstantSet()) {
                throw new IllegalStateException("instant has been set");
            }
            if ((nextStamp + len / 2) < 0) {
                throw new IllegalStateException("stamp counter overflow");
            }
            allocateFields();
            for (int i = 0; i < len; ) {
                int field = fieldValuePairs[i++];
                // Note: WEEK_YEAR can't be set with this method.
                if (field < 0 || field >= FIELD_COUNT) {
                    throw new IllegalArgumentException("field is invalid");
                }
                internalSet(field, fieldValuePairs[i++]);
            }
            return this;
        }

        /**
         * Sets the date field parameters to the values given by {@code year},
         * {@code month}, and {@code dayOfMonth}. This method is equivalent to
         * a call to:
         * <pre>
         *   setFields(Calendar.YEAR, year,
         *             Calendar.MONTH, month,
         *             Calendar.DAY_OF_MONTH, dayOfMonth);</pre>
         *
         * @param year       the {@link Calendar#YEAR YEAR} value
         * @param month      the {@link Calendar#MONTH MONTH} value
         *                   (the month numbering is <em>0-based</em>).
         * @param dayOfMonth the {@link Calendar#DAY_OF_MONTH DAY_OF_MONTH} value
         * @return this {@code Calendar.Builder}
         */
        public CalendarBuilder setDate(int year, int month, int dayOfMonth) {
            return setFields(Calendar.YEAR, year, Calendar.MONTH, month,
                             Calendar.DAY_OF_MONTH, dayOfMonth);
        }

        /**
         * Sets the time of day field parameters to the values given by
         * {@code hourOfDay}, {@code minute}, and {@code second}. This method is
         * equivalent to a call to:
         * <pre>
         *   setTimeOfDay(hourOfDay, minute, second, 0);</pre>
         *
         * @param hourOfDay the {@link Calendar#HOUR_OF_DAY HOUR_OF_DAY} value
         *                  (24-hour clock)
         * @param minute    the {@link Calendar#MINUTE MINUTE} value
         * @param second    the {@link Calendar#SECOND SECOND} value
         * @return this {@code Calendar.Builder}
         */
        public CalendarBuilder setTimeOfDay(int hourOfDay, int minute, int second) {
            return setTimeOfDay(hourOfDay, minute, second, 0);
        }

        /**
         * Sets the time of day field parameters to the values given by
         * {@code hourOfDay}, {@code minute}, {@code second}, and
         * {@code millis}. This method is equivalent to a call to:
         * <pre>
         *   setFields(Calendar.HOUR_OF_DAY, hourOfDay,
         *             Calendar.MINUTE, minute,
         *             Calendar.SECOND, second,
         *             Calendar.MILLISECOND, millis);</pre>
         *
         * @param hourOfDay the {@link Calendar#HOUR_OF_DAY HOUR_OF_DAY} value
         *                  (24-hour clock)
         * @param minute    the {@link Calendar#MINUTE MINUTE} value
         * @param second    the {@link Calendar#SECOND SECOND} value
         * @param millis    the {@link Calendar#MILLISECOND MILLISECOND} value
         * @return this {@code Calendar.Builder}
         */
        public CalendarBuilder setTimeOfDay(int hourOfDay, int minute, int second, int millis) {
            return setFields(Calendar.HOUR_OF_DAY, hourOfDay, Calendar.MINUTE, minute,
                             Calendar.SECOND, second, Calendar.MILLISECOND, millis);
        }

        /**
         * Sets the week-based date parameters to the values with the given
         * date specifiers - week year, week of year, and day of week.
         *
         * <p>If the specified calendar doesn't support week dates, the
         * {@link #build() build} method will throw an {@link IllegalArgumentException}.
         *
         * @param weekYear   the week year
         * @param weekOfYear the week number based on {@code weekYear}
         * @param dayOfWeek  the day of week value: one of the constants
         *     for the {@link Calendar#DAY_OF_WEEK DAY_OF_WEEK} field:
         *     {@link Calendar#SUNDAY SUNDAY}, ..., {@link Calendar#SATURDAY SATURDAY}.
         * @return this {@code Calendar.Builder}
         * @see Calendar#setWeekDate(int, int, int)
         * @see Calendar#isWeekDateSupported()
         */
        public CalendarBuilder setWeekDate(int weekYear, int weekOfYear, int dayOfWeek) {
            allocateFields();
            internalSet(WEEK_YEAR, weekYear);
            internalSet(Calendar.WEEK_OF_YEAR, weekOfYear);
            internalSet(Calendar.DAY_OF_WEEK, dayOfWeek);
            return this;
        }

        /**
         * Sets the time zone parameter to the given {@code zone}. If no time
         * zone parameter is given to this {@code Caledar.Builder}, the
         * {@linkplain TimeZone#getDefault() default
         * <code>TimeZone</code>} will be used in the {@link #build() build}
         * method.
         *
         * @param zone the {@link TimeZone}
         * @return this {@code Calendar.Builder}
         * @throws NullPointerException if {@code zone} is {@code null}
         * @see Calendar#setTimeZone(TimeZone)
         */
        public CalendarBuilder setTimeZone(TimeZone zone) {
            if (zone == null) {
                throw new NullPointerException();
            }
            this.zone = zone;
            return this;
        }

        /**
         * Sets the lenient mode parameter to the value given by {@code lenient}.
         * If no lenient parameter is given to this {@code Calendar.Builder},
         * lenient mode will be used in the {@link #build() build} method.
         *
         * @param lenient {@code true} for lenient mode;
         *                {@code false} for non-lenient mode
         * @return this {@code Calendar.Builder}
         * @see Calendar#setLenient(boolean)
         */
        public CalendarBuilder setLenient(boolean lenient) {
            this.lenient = lenient;
            return this;
        }

        /**
         * Sets the calendar type parameter to the given {@code type}. The
         * calendar type given by this method has precedence over any explicit
         * or implicit calendar type given by the
         * {@linkplain #setLocale(Locale) locale}.
         *
         * <p>In addition to the available calendar types returned by the
         * {@link Calendar#getAvailableCalendarTypes() Calendar.getAvailableCalendarTypes}
         * method, {@code "gregorian"} and {@code "iso8601"} as aliases of
         * {@code "gregory"} can be used with this method.
         *
         * @param type the calendar type
         * @return this {@code Calendar.Builder}
         * @throws NullPointerException if {@code type} is {@code null}
         * @throws IllegalArgumentException if {@code type} is unknown
         * @throws IllegalStateException if another calendar type has already been set
         * @see Calendar#getCalendarType()
         * @see Calendar#getAvailableCalendarTypes()
         */
        public CalendarBuilder setCalendarType(String type) {
            if (type.equals("gregorian")) { // NPE if type == null
                type = "gregory";
            }
            if (!AvailableCalendarTypes.SET.contains(type)
                    && !type.equals("iso8601")) {
                throw new IllegalArgumentException("unknown calendar type: " + type);
            }
            if (this.type == null) {
                this.type = type;
            } else {
                if (!this.type.equals(type)) {
                    throw new IllegalStateException("calendar type override");
                }
            }
            return this;
        }

        /**
         * Sets the locale parameter to the given {@code locale}. If no locale
         * is given to this {@code Calendar.Builder}, the {@linkplain
         * Locale#getDefault(Locale.Category) default <code>Locale</code>}
         * for {@link Locale.Category#FORMAT} will be used.
         *
         * <p>If no calendar type is explicitly given by a call to the
         * {@link #setCalendarType(String) setCalendarType} method,
         * the {@code Locale} value is used to determine what type of
         * {@code Calendar} to be built.
         *
         * <p>If no week definition parameters are explicitly given by a call to
         * the {@link #setWeekDefinition(int,int) setWeekDefinition} method, the
         * {@code Locale}'s default values are used.
         *
         * @param locale the {@link Locale}
         * @throws NullPointerException if {@code locale} is {@code null}
         * @return this {@code Calendar.Builder}
         * @see Calendar#getInstance(Locale)
         */
        public CalendarBuilder setLocale(Locale locale) {
            if (locale == null) {
                throw new NullPointerException();
            }
            this.locale = locale;
            return this;
        }

        /**
         * Sets the week definition parameters to the values given by
         * {@code firstDayOfWeek} and {@code minimalDaysInFirstWeek} that are
         * used to determine the <a href="Calendar.html#First_Week">first
         * week</a> of a year. The parameters given by this method have
         * precedence over the default values given by the
         * {@linkplain #setLocale(Locale) locale}.
         *
         * @param firstDayOfWeek the first day of a week; one of
         *                       {@link Calendar#SUNDAY} to {@link Calendar#SATURDAY}
         * @param minimalDaysInFirstWeek the minimal number of days in the first
         *                               week (1..7)
         * @return this {@code Calendar.Builder}
         * @throws IllegalArgumentException if {@code firstDayOfWeek} or
         *                                  {@code minimalDaysInFirstWeek} is invalid
         * @see Calendar#getFirstDayOfWeek()
         * @see Calendar#getMinimalDaysInFirstWeek()
         */
        public CalendarBuilder setWeekDefinition(int firstDayOfWeek, int minimalDaysInFirstWeek) {
            if (!isValidWeekParameter(firstDayOfWeek)
                    || !isValidWeekParameter(minimalDaysInFirstWeek)) {
                throw new IllegalArgumentException();
            }
            this.firstDayOfWeek = firstDayOfWeek;
            this.minimalDaysInFirstWeek = minimalDaysInFirstWeek;
            return this;
        }

        /**
         * Returns a {@code Calendar} built from the parameters set by the
         * setter methods. The calendar type given by the {@link #setCalendarType(String)
         * setCalendarType} method or the {@linkplain #setLocale(Locale) locale} is
         * used to determine what {@code Calendar} to be created. If no explicit
         * calendar type is given, the locale's default calendar is created.
         *
         * <p>If the calendar type is {@code "iso8601"}, the
         * {@linkplain GregorianCalendar#setGregorianChange(Date) Gregorian change date}
         * of a {@link GregorianCalendar} is set to {@code Date(Long.MIN_VALUE)}
         * to be the <em>proleptic</em> Gregorian calendar. Its week definition
         * parameters are also set to be <a
         * href="GregorianCalendar.html#iso8601_compatible_setting">compatible
         * with the ISO 8601 standard</a>. Note that the
         * {@link GregorianCalendar#getCalendarType() getCalendarType} method of
         * a {@code GregorianCalendar} created with {@code "iso8601"} returns
         * {@code "gregory"}.
         *
         * <p>The default values are used for locale and time zone if these
         * parameters haven't been given explicitly.
         *
         * <p>Any out of range field values are either normalized in lenient
         * mode or detected as an invalid value in non-lenient mode.
         *
         * @return a {@code Calendar} built with parameters of this {@code
         *         Calendar.Builder}
         * @throws IllegalArgumentException if the calendar type is unknown, or
         *             if any invalid field values are given in non-lenient mode, or
         *             if a week date is given for the calendar type that doesn't
         *             support week dates.
         * @see Calendar#getInstance(TimeZone, Locale)
         * @see Locale#getDefault(Locale.Category)
         * @see TimeZone#getDefault()
         */
        public Calendar build() {
            if (locale == null) {
                locale = Locale.getDefault();
            }
            if (zone == null) {
                zone = TimeZone.getDefault();
            }
            Calendar cal;
            JavaUtilCalendarAccess access = SharedSecrets.getJavaUtilCalendarAccess();
            if (type == null) {
                type = locale.getUnicodeLocaleType("ca");
            }
            if (type == null) {
                if (locale.getCountry() == "TH"
                    && locale.getLanguage() == "th") {
                    type = "buddhist";
                } else {
                    type = "gregory";
                }
            }
            switch (type) {
            case "gregory":
                cal = access.createCalendar(zone, locale);
                break;
            case "iso8601":
                GregorianCalendar gcal = access.createCalendar(zone, locale);
                // make gcal a proleptic Gregorian
                gcal.setGregorianChange(new Date(Long.MIN_VALUE));
                // and week definition to be compatible with ISO 8601
                setWeekDefinition(Calendar.MONDAY, 4);
                cal = gcal;
                break;
            default:
                throw new IllegalArgumentException("unknown calendar type: " + type);
            }
            cal.setLenient(lenient);
            if (firstDayOfWeek != 0) {
                cal.setFirstDayOfWeek(firstDayOfWeek);
                cal.setMinimalDaysInFirstWeek(minimalDaysInFirstWeek);
            }
            if (isInstantSet()) {
                cal.setTimeInMillis(instant);
                access.complete(cal);
                return cal;
            }

            if (fields != null) {
                boolean weekDate = isSet(WEEK_YEAR)
                                       && fields[WEEK_YEAR] > fields[Calendar.YEAR];
                if (weekDate && !cal.isWeekDateSupported()) {
                    throw new IllegalArgumentException("week date is unsupported by " + type);
                }

                // Set the fields from the min stamp to the max stamp so that
                // the fields resolution works in the Calendar.
                for (int stamp = MINIMUM_USER_STAMP; stamp < nextStamp; stamp++) {
                    for (int index = 0; index <= maxFieldIndex; index++) {
                        if (fields[index] == stamp) {
                            cal.set(index, fields[NFIELDS + index]);
                            break;
                        }
                    }
                }

                if (weekDate) {
                    int weekOfYear = isSet(Calendar.WEEK_OF_YEAR) ? fields[NFIELDS + Calendar.WEEK_OF_YEAR] : 1;
                    int dayOfWeek = isSet(Calendar.DAY_OF_WEEK)
                                    ? fields[NFIELDS + Calendar.DAY_OF_WEEK] : cal.getFirstDayOfWeek();
                    cal.setWeekDate(fields[NFIELDS + WEEK_YEAR], weekOfYear, dayOfWeek);
                }
                access.complete(cal);
            }

            return cal;
        }

        private void allocateFields() {
            if (fields == null) {
                fields = new int[NFIELDS * 2];
                nextStamp = MINIMUM_USER_STAMP;
                maxFieldIndex = -1;
            }
        }

        private void internalSet(int field, int value) {
            fields[field] = nextStamp++;
            if (nextStamp < 0) {
                throw new IllegalStateException("stamp counter overflow");
            }
            fields[NFIELDS + field] = value;
            if (field > maxFieldIndex && field < WEEK_YEAR) {
                maxFieldIndex = field;
            }
        }

        private boolean isInstantSet() {
            return nextStamp == COMPUTED;
        }

        private boolean isSet(int index) {
            return fields != null && fields[index] > UNSET;
        }

        private boolean isValidWeekParameter(int value) {
            return value > 0 && value <= 7;
        }
    }

    private static class AvailableCalendarTypes {
        private static final Set<String> SET;
        static {
            Set<String> set = new HashSet<>(3);
            set.add("gregory");
            set.add("buddhist");
            set.add("japanese");
            SET = Collections.unmodifiableSet(set);
        }
        private AvailableCalendarTypes() {
        }
    }

}
