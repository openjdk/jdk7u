/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @summary Objects.checkIndex/jdk.internal.util.Preconditions.checkIndex tests
 * @run testng CheckIndex
 * @bug 8135248 8142493 8155794
 */

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import sun.security.util.BiConsumer;
import sun.security.util.BiFunction;
import sun.security.util.Function;
import sun.security.util.IntSupplier;
import sun.security.util.Preconditions;

import static org.testng.Assert.*;

public class CheckIndex {

    private static final Function<String, IndexOutOfBoundsException> ioobeGenerator =
        new Function<String, IndexOutOfBoundsException>() {
           @Override
           public IndexOutOfBoundsException apply(String x) {
               return new IndexOutOfBoundsException(x);
           }
        };

    private static final Function<String, StringIndexOutOfBoundsException> sioobeGenerator =
        new Function<String, StringIndexOutOfBoundsException>() {
           @Override
           public StringIndexOutOfBoundsException apply(String x) {
               return new StringIndexOutOfBoundsException(x);
           }
        };

    private static final Function<String, ArrayIndexOutOfBoundsException> aioobeGenerator =
        new Function<String, ArrayIndexOutOfBoundsException>() {
           @Override
           public ArrayIndexOutOfBoundsException apply(String x) {
               return new ArrayIndexOutOfBoundsException(x);
           }
        };

    static class AssertingOutOfBoundsException extends RuntimeException {
        public AssertingOutOfBoundsException(String message) {
            super(message);
        }
    }

    static BiFunction<String, List<Integer>, AssertingOutOfBoundsException> assertingOutOfBounds(
            final String message, final String expCheckKind, final Integer... expArgs) {
        return new BiFunction<String, List<Integer>, AssertingOutOfBoundsException>() {
            @Override
            public AssertingOutOfBoundsException apply(String checkKind, List<Integer> args) {
                assertEquals(checkKind, expCheckKind);
                assertEquals(args, Collections.unmodifiableList(Arrays.asList(expArgs)));
                try {
                    args.clear();
                    fail("Out of bounds List<Integer> argument should be unmodifiable");
                } catch (Exception e)  {
                }
                return new AssertingOutOfBoundsException(message);
            }
        };
    }

    static BiFunction<String, List<Integer>, AssertingOutOfBoundsException> assertingOutOfBoundsReturnNull(
            final String expCheckKind, final Integer... expArgs) {
        return new BiFunction<String, List<Integer>, AssertingOutOfBoundsException>() {
            @Override
            public AssertingOutOfBoundsException apply(String checkKind, List<Integer> args) {
                assertEquals(checkKind, expCheckKind);
                assertEquals(args, Collections.unmodifiableList(Arrays.asList(expArgs)));
                return null;
            }
        };
    }

    static final int[] VALUES = {0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, -1, Integer.MIN_VALUE + 1, Integer.MIN_VALUE};

    @DataProvider
    static Object[][] checkIndexProvider() {
        List<Object[]> l = new ArrayList<>();
        for (int index : VALUES) {
            for (int length : VALUES) {
                boolean withinBounds = index >= 0 &&
                                       length >= 0 &&
                                       index < length;
                l.add(new Object[]{index, length, withinBounds});
            }
        }
        return l.toArray(new Object[0][0]);
    }

    interface X {
        int apply(int a, int b, int c);
    }

    @Test(dataProvider = "checkIndexProvider")
    public void testCheckIndex(final int index, final int length, final boolean withinBounds) {
        List<Integer> list = Collections.unmodifiableList(Arrays.asList(new Integer[] { index, length }));
        final String expectedMessage = withinBounds
                                       ? null
                                       : Preconditions.outOfBoundsExceptionFormatter(ioobeGenerator).
                apply("checkIndex", list).getMessage();

        BiConsumer<Class<? extends RuntimeException>, IntSupplier> checker =
            new BiConsumer<Class<? extends RuntimeException>, IntSupplier>() {
            @Override
            public void accept(Class<? extends RuntimeException> ec, IntSupplier s) {
                try {
                    int rIndex = s.getAsInt();
                    if (!withinBounds)
                        fail(String.format(
                            "Index %d is out of bounds of [0, %d), but was reported to be within bounds", index, length));
                    assertEquals(rIndex, index);
                }
                catch (RuntimeException e) {
                    assertTrue(ec.isInstance(e));
                    if (withinBounds)
                        fail(String.format(
                            "Index %d is within bounds of [0, %d), but was reported to be out of bounds", index, length));
                    else
                        assertEquals(e.getMessage(), expectedMessage);
                }
            }
        };

        checker.accept(AssertingOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkIndex(index, length,
                                                assertingOutOfBounds(expectedMessage, "checkIndex", index, length));
            }
        });
        checker.accept(IndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkIndex(index, length,
                                                assertingOutOfBoundsReturnNull("checkIndex", index, length));
            }
        });
        checker.accept(IndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkIndex(index, length, null);
            }
        });
        checker.accept(ArrayIndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkIndex(index, length,
                                                Preconditions.outOfBoundsExceptionFormatter(aioobeGenerator));
            }
        });
        checker.accept(StringIndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkIndex(index, length,
                                                Preconditions.outOfBoundsExceptionFormatter(sioobeGenerator));
            }
        });
    }


    @DataProvider
    static Object[][] checkFromToIndexProvider() {
        List<Object[]> l = new ArrayList<>();
        for (int fromIndex : VALUES) {
            for (int toIndex : VALUES) {
                for (int length : VALUES) {
                    boolean withinBounds = fromIndex >= 0 &&
                                           toIndex >= 0 &&
                                           length >= 0 &&
                                           fromIndex <= toIndex &&
                                           toIndex <= length;
                    l.add(new Object[]{fromIndex, toIndex, length, withinBounds});
                }
            }
        }
        return l.toArray(new Object[0][0]);
    }

    @Test(dataProvider = "checkFromToIndexProvider")
    public void testCheckFromToIndex(final int fromIndex, final int toIndex,
                                     final int length, final boolean withinBounds) {
        List<Integer> list = Collections.unmodifiableList(Arrays.asList(new Integer[] { fromIndex, toIndex, length }));
        final String expectedMessage = withinBounds
                                       ? null
                                       : Preconditions.outOfBoundsExceptionFormatter(ioobeGenerator).
                apply("checkFromToIndex", list).getMessage();

        BiConsumer<Class<? extends RuntimeException>, IntSupplier> check =
            new BiConsumer<Class<? extends RuntimeException>, IntSupplier>() {
            @Override
            public void accept(Class<? extends RuntimeException> ec, IntSupplier s) {
                try {
                    int rIndex = s.getAsInt();
                    if (!withinBounds)
                        fail(String.format(
                            "Range [%d, %d) is out of bounds of [0, %d), but was reported to be withing bounds", fromIndex, toIndex, length));
                    assertEquals(rIndex, fromIndex);
                }
                catch (RuntimeException e) {
                    assertTrue(ec.isInstance(e));
                    if (withinBounds)
                        fail(String.format(
                            "Range [%d, %d) is within bounds of [0, %d), but was reported to be out of bounds", fromIndex, toIndex, length));
                    else
                        assertEquals(e.getMessage(), expectedMessage);
                }
            }
        };

        check.accept(AssertingOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromToIndex(fromIndex, toIndex, length,
                                                      assertingOutOfBounds(expectedMessage, "checkFromToIndex", fromIndex, toIndex, length));
            }
        });
        check.accept(IndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return  Preconditions.checkFromToIndex(fromIndex, toIndex, length,
                                                       assertingOutOfBoundsReturnNull("checkFromToIndex", fromIndex, toIndex, length));
            }
        });
        check.accept(IndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromToIndex(fromIndex, toIndex, length, null);
            }
        });
        check.accept(ArrayIndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromToIndex(fromIndex, toIndex, length,
                                                      Preconditions.outOfBoundsExceptionFormatter(aioobeGenerator));
            }
        });
        check.accept(StringIndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromToIndex(fromIndex, toIndex, length,
                                                      Preconditions.outOfBoundsExceptionFormatter(sioobeGenerator));
            }
        });
    }


    @DataProvider
    static Object[][] checkFromIndexSizeProvider() {
        List<Object[]> l = new ArrayList<>();
        for (int fromIndex : VALUES) {
            for (int size : VALUES) {
                for (int length : VALUES) {
                    // Explicitly convert to long
                    long lFromIndex = fromIndex;
                    long lSize = size;
                    long lLength = length;
                    // Avoid overflow
                    long lToIndex = lFromIndex + lSize;

                    boolean withinBounds = lFromIndex >= 0L &&
                                           lSize >= 0L &&
                                           lLength >= 0L &&
                                           lFromIndex <= lToIndex &&
                                           lToIndex <= lLength;
                    l.add(new Object[]{fromIndex, size, length, withinBounds});
                }
            }
        }
        return l.toArray(new Object[0][0]);
    }

    @Test(dataProvider = "checkFromIndexSizeProvider")
    public void testCheckFromIndexSize(final int fromIndex, final int size,
                                       final int length, final boolean withinBounds) {
        List<Integer> list = Collections.unmodifiableList(Arrays.asList(new Integer[] { fromIndex, size, length }));
        final String expectedMessage = withinBounds
                                       ? null
                                       : Preconditions.outOfBoundsExceptionFormatter(ioobeGenerator).
                apply("checkFromIndexSize", list).getMessage();

        BiConsumer<Class<? extends RuntimeException>, IntSupplier> check =
            new BiConsumer<Class<? extends RuntimeException>, IntSupplier>() {
                @Override
                public void accept(Class<? extends RuntimeException> ec, IntSupplier s) {
                    try {
                        int rIndex = s.getAsInt();
                        if (!withinBounds)
                            fail(String.format(
                                "Range [%d, %d + %d) is out of bounds of [0, %d), but was reported to be withing bounds", fromIndex, fromIndex, size, length));
                        assertEquals(rIndex, fromIndex);
                    }
                    catch (RuntimeException e) {
                        assertTrue(ec.isInstance(e));
                        if (withinBounds)
                            fail(String.format(
                                "Range [%d, %d + %d) is within bounds of [0, %d), but was reported to be out of bounds", fromIndex, fromIndex, size, length));
                else
                    assertEquals(e.getMessage(), expectedMessage);
                    }
                }
            };

        check.accept(AssertingOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromIndexSize(fromIndex, size, length,
                                                        assertingOutOfBounds(expectedMessage, "checkFromIndexSize", fromIndex, size, length));
            }
        });
        check.accept(IndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromIndexSize(fromIndex, size, length,
                                                        assertingOutOfBoundsReturnNull("checkFromIndexSize", fromIndex, size, length));
            }
        });
        check.accept(IndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromIndexSize(fromIndex, size, length, null);
            }
        });
        check.accept(ArrayIndexOutOfBoundsException.class, new IntSupplier() {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromIndexSize(fromIndex, size, length,
                                                        Preconditions.outOfBoundsExceptionFormatter(aioobeGenerator));
            }
        });
        check.accept(StringIndexOutOfBoundsException.class, new IntSupplier () {
            @Override
            public int getAsInt() {
                return Preconditions.checkFromIndexSize(fromIndex, size, length,
                                                        Preconditions.outOfBoundsExceptionFormatter(sioobeGenerator));
            }
        });
    }

    @Test
    public void uniqueMessagesForCheckKinds() {
        BiFunction<String, List<Integer>, IndexOutOfBoundsException> f =
                Preconditions.outOfBoundsExceptionFormatter(ioobeGenerator);

        List<String> messages = new ArrayList<>();
        List<Integer> arg1 = Collections.unmodifiableList(Arrays.asList(new Integer[] { -1 }));
        List<Integer> arg2 = Collections.unmodifiableList(Arrays.asList(new Integer[] { -1, 0 }));
        List<Integer> arg3 = Collections.unmodifiableList(Arrays.asList(new Integer[] { -1, 0, 0 }));
        List<Integer> arg4 = Collections.unmodifiableList(Arrays.asList(new Integer[] { -1, 0, 0, 0 }));
        // Exact arguments
        messages.add(f.apply("checkIndex", arg2).getMessage());
        messages.add(f.apply("checkFromToIndex", arg3).getMessage());
        messages.add(f.apply("checkFromIndexSize", arg3).getMessage());
        // Unknown check kind
        messages.add(f.apply("checkUnknown", arg3).getMessage());
        // Known check kind with more arguments
        messages.add(f.apply("checkIndex", arg3).getMessage());
        messages.add(f.apply("checkFromToIndex", arg4).getMessage());
        messages.add(f.apply("checkFromIndexSize", arg4).getMessage());
        // Known check kind with fewer arguments
        messages.add(f.apply("checkIndex", arg1).getMessage());
        messages.add(f.apply("checkFromToIndex", arg2).getMessage());
        messages.add(f.apply("checkFromIndexSize", arg2).getMessage());
        // Null arguments
        messages.add(f.apply(null, null).getMessage());
        messages.add(f.apply("checkNullArguments", null).getMessage());
        messages.add(f.apply(null, arg1).getMessage());

        Set<String> distinct = new HashSet<>(messages);
        assertEquals(messages.size(), distinct.size());
    }
}
