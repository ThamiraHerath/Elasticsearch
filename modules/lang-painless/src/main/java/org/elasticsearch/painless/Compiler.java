/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless;

import org.elasticsearch.bootstrap.BootstrapInfo;
import org.elasticsearch.painless.antlr.Walker;
import org.elasticsearch.painless.node.SSource;
import org.objectweb.asm.util.Printer;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;

import static org.elasticsearch.painless.WriterConstants.CLASS_NAME;

/**
 * The Compiler is the entry point for generating a Painless script.  The compiler will receive a Painless
 * tree based on the type of input passed in (currently only ANTLR).  Two passes will then be run over the tree,
 * one for analysis and another to generate the actual byte code using ASM using the root of the tree {@link SSource}.
 */
final class Compiler {

    /**
     * The maximum number of characters allowed in the script source.
     */
    static final int MAXIMUM_SOURCE_LENGTH = 16384;

    /**
     * Define the class with lowest privileges.
     */
    private static final CodeSource CODESOURCE;

    /**
     * Setup the code privileges.
     */
    static {
        try {
            // Setup the code privileges.
            CODESOURCE = new CodeSource(new URL("file:" + BootstrapInfo.UNTRUSTED_CODEBASE), (Certificate[]) null);
        } catch (MalformedURLException impossible) {
            throw new RuntimeException(impossible);
        }
    }

    /**
     * A secure class loader used to define Painless scripts.
     */
    static final class Loader extends SecureClassLoader {
        /**
         * @param parent The parent ClassLoader.
         */
        Loader(ClassLoader parent) {
            super(parent);
        }

        /**
         * Generates a Class object from the generated byte code.
         * @param name The name of the class.
         * @param bytes The generated byte code.
         * @return A Class object extending {@link PainlessScript}.
         */
        Class<? extends PainlessScript> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length, CODESOURCE).asSubclass(PainlessScript.class);
        }
    }

    /**
     * Runs the two-pass compiler to generate a Painless script.
     * @param <T> the type of the script
     * @param loader The ClassLoader used to define the script.
     * @param iface Interface the compiled script should implement
     * @param name The name of the script.
     * @param source The source code for the script.
     * @param settings The CompilerSettings to be used during the compilation.
     * @return An executable script that implements both {@code <T>} and is a subclass of {@link PainlessScript}
     */
    static <T> T compile(Loader loader, Class<T> iface, String name, String source, CompilerSettings settings) {
        if (source.length() > MAXIMUM_SOURCE_LENGTH) {
            throw new IllegalArgumentException("Scripts may be no longer than " + MAXIMUM_SOURCE_LENGTH +
                " characters.  The passed in script is " + source.length() + " characters.  Consider using a" +
                " plugin if a script longer than this length is a requirement.");
        }
        ScriptInterface scriptInterface = new ScriptInterface(iface);

        SSource root = Walker.buildPainlessTree(scriptInterface, name, source, settings, null);

        root.analyze();
        root.write();

        try {
            Class<? extends PainlessScript> clazz = loader.define(CLASS_NAME, root.getBytes());
            java.lang.reflect.Constructor<? extends PainlessScript> constructor =
                    clazz.getConstructor(PainlessScript.ScriptMetadata.class);
            PainlessScript.ScriptMetadata scriptMetadata =
                    new PainlessScript.ScriptMetadata(name, source, root.getStatements());

            return iface.cast(constructor.newInstance(scriptMetadata));
        } catch (Exception exception) { // Catch everything to let the user know this is something caused internally.
            throw new IllegalStateException("An internal error occurred attempting to define the script [" + name + "].", exception);
        }
    }

    /**
     * Runs the two-pass compiler to generate a Painless script.  (Used by the debugger.)
     * @param iface Interface the compiled script should implement
     * @param source The source code for the script.
     * @param settings The CompilerSettings to be used during the compilation.
     * @return The bytes for compilation.
     */
    static byte[] compile(Class<?> iface, String name, String source, CompilerSettings settings, Printer debugStream) {
        if (source.length() > MAXIMUM_SOURCE_LENGTH) {
            throw new IllegalArgumentException("Scripts may be no longer than " + MAXIMUM_SOURCE_LENGTH +
                " characters.  The passed in script is " + source.length() + " characters.  Consider using a" +
                " plugin if a script longer than this length is a requirement.");
        }
        ScriptInterface scriptInterface = new ScriptInterface(iface);

        SSource root = Walker.buildPainlessTree(scriptInterface, name, source, settings, debugStream);

        root.analyze();
        root.write();

        return root.getBytes();
    }

    /**
     * All methods in the compiler should be static.
     */
    private Compiler() {}
}
