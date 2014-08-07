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

package org.elasticsearch.common.cli;

import org.elasticsearch.common.Strings;
import org.elasticsearch.test.ElasticsearchTestCase;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 *
 */
public class CliToolTestCase extends ElasticsearchTestCase {

    protected static String[] args(String command) {
        if (!Strings.hasLength(command)) {
            return Strings.EMPTY_ARRAY;
        }
        return command.split("\\s+");
    }

    public static class TerminalMock extends Terminal {

        private static final PrintWriter DEV_NULL = new PrintWriter(new DevNullWriter());

        public TerminalMock() {
            super(Verbosity.NORMAL);
        }

        public TerminalMock(Verbosity verbosity) {
            super(verbosity);
        }

        @Override
        protected void doPrint(String msg, Object... args) {
        }

        @Override
        public String readText(String text, Object... args) {
            return null;
        }

        @Override
        public char[] readSecret(String text, Object... args) {
            return new char[0];
        }

        @Override
        public void print(String msg, Object... args) {
        }

        @Override
        public PrintWriter writer() {
            return DEV_NULL;
        }

        private static class DevNullWriter extends Writer {

            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
            }

            @Override
            public void flush() throws IOException {
            }

            @Override
            public void close() throws IOException {
            }
        }
    }

}
