/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8218781
 * @summary Test the localized names of Japanese era Reiwa from COMPAT provider.
 * @modules jdk.localedata
 * @run testng/othervm -Djava.locale.providers=COMPAT JapanEraNameCompatTest
 */

import static java.util.Calendar.*;
import static java.util.Locale.*;

import java.util.Calendar;
import java.util.Locale;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

@Test
public class JapanEraNameCompatTest {
    static final Calendar c;
    static final String EngName = "Reiwa";
    static final String CJName = "\u4ee4\u548c";
    static final String KoreanName = "\ub808\uc774\uc640";
    static final String ArabicName = "\u0631\u064a\u0648\u0627";
    static final String ThaiName = "\u0e40\u0e23\u0e27\u0e30";

    static {
        c = Calendar.getInstance(new Locale("ja","JP","JP"));
        c.set(ERA, 5);
        c.set(1, MAY, 1);
    }

    @DataProvider(name="UtilCalendar")
    Object[][] dataUtilCalendar() {
        return new Object[][] {
            //locale,   long,       short
            { US,       EngName,    "R" },
            { JAPAN,    CJName,     "R" },
            { KOREAN,   KoreanName, "R" },
            { CHINA,    CJName,     "R" },
            { TAIWAN,   CJName,     "R" }, // fallback to zh
            { new Locale("ar"), ArabicName, ArabicName },
            { new Locale("th"), ThaiName, "R" },
            // hi_IN fallback to root
            { new Locale("hi", "IN"), EngName, "R" }
        };
    }

    @Test(dataProvider="UtilCalendar")
    public void testCalendarEraDisplayName(Locale locale,
            String longName, String shortName) {
        assertEquals(c.getDisplayName(ERA, LONG, locale), longName);
        assertEquals(c.getDisplayName(ERA, SHORT, locale), shortName);
    }

}
