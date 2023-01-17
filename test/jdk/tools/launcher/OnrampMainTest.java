/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @test
 * @summary test execution priority of main methods
 * @run main OnrampMainTest
 */
public class OnrampMainTest extends TestHelper {

    @Test
    public void testStaticMainArgs() throws Exception {
        test("""
            class MainClass {
                static void main() {
                    throw new AssertionError();
                }
                static void main(String[] args) {
                }
            }
            """);
    }

    @Test
    public void testStaticMain() throws Exception {
        test("""
            class MainClass {
                void main(String[] args) {
                    throw new AssertionError();
                }
                static void main() {
                }
            }
            """);
    }

    @Test
    public void testMainArgs() throws Exception {
        test("""
            class MainClass {
                void main() {
                    throw new AssertionError();
                }
                void main(String[] args) {
                }
            }
            """);
    }

    @Test
    public void testMain() throws Exception {
        test("""
            class MainClass {
                void main() {
                }
            }
            """);
    }

    @Test
    public void testImplicitStaticMainArgs() throws Exception {
        test("""
            static void main() {
                throw new AssertionError();
            }
            static void main(String[] args) {
            }
            """);
    }

    @Test
    public void testImplicitStaticMain() throws Exception {
        test("""
            void main(String[] args) {
                throw new AssertionError();
            }
            static void main() {
            }
            """);
    }

    @Test
    public void testImplicitMainArgs() throws Exception {
        test("""
            void main() {
                throw new AssertionError();
            }
            void main(String[] args) {
            }
            """);
    }

    @Test
    public void testImplicitMain() throws Exception {
        test("""
            void main() {
            }
            """);
    }

    void test(String source) throws Exception {
        Files.writeString(Path.of("MainClass.java"), source);
        var tr = doExec(javaCmd, "MainClass.java");
        if (!tr.isOK()) {
            System.out.println(tr);
            throw new AssertionError();
        }
    }

    public static void main(String... args) throws Exception {
        new OnrampMainTest().run(args);
    }
}
