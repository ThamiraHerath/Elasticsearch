/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.internal.doc;

import groovy.transform.PackageScope;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

public abstract class RestTestsFromDocSnippetTask extends DocSnippetTask {

    /**
     * For easier migration from asciidoc to mdx we support a migration mode that
     * allows generation from the same file name but different extensions. The task
     * will compare the generated tests from the asciidoc and mdx files and fail if
     * they are not equal (ignoring the line numbers).
     * */
    @Input
    public abstract Property<Boolean> getMigrationMode();

    /**
     * Test setups defined in the build instead of the docs so they can be
     * shared between many doc files.
     */
    private Map<String, String> setups = new LinkedHashMap<>();

    @Input
    public Map<String, String> getSetups() {
        return setups;
    }

    /**
     * Test teardowns defined in the build instead of the docs so they can be
     * shared between many doc files.
     */
    @Input
    public abstract MapProperty<String, String> getTeardowns();

    /**
     * A list of files that contain snippets that *probably* should be
     * converted to `// CONSOLE` but have yet to be converted. If a file is in
     * this list and doesn't contain unconverted snippets this task will fail.
     * If there are unconverted snippets not in this list then this task will
     * fail. All files are paths relative to the docs dir.
     */
    @Input
    public abstract ListProperty<String> getExpectedUnconvertedCandidates();

    /**
     * Root directory containing all the files generated by this task. It is
     * contained within testRoot.
     */
    @OutputDirectory
    File getOutputRoot() {
        return new File(getTestRoot().get().getAsFile(), "/rest-api-spec/test");
    }

    /**
     * Root directory of the tests being generated. To make rest tests happy
     * we generate them in a testRoot which is contained in this directory.
     */
    @Internal
    abstract DirectoryProperty getTestRoot();

    @Inject
    public RestTestsFromDocSnippetTask() {
        TestBuilder builder = new TestBuilder();
        setPerSnippet(builder::handleSnippet);
        getMigrationMode().convention(false);
        doLast(task -> {
            builder.finishLastTest();
            builder.checkUnconverted();
            if (getMigrationMode().get()) {
                assertEqualTestSnippetFromMigratedDocs();
            }
        });
    }

    /**
     * Certain requests should not have the shard failure check because the
     * format of the response is incompatible i.e. it is not a JSON object.
     */
    static boolean shouldAddShardFailureCheck(String path) {
        return path.startsWith("_cat") == false && path.startsWith("_ml/datafeeds/") == false;
    }

    /**
     * Converts Kibana's block quoted strings into standard JSON. These
     * {@code """} delimited strings can be embedded in CONSOLE and can
     * contain newlines and {@code "} without the normal JSON escaping.
     * This has to add it.
     */
    @PackageScope
    static String replaceBlockQuote(String body) {
        int start = body.indexOf("\"\"\"");
        if (start < 0) {
            return body;
        }
        /*
         * 1.3 is a fairly wild guess of the extra space needed to hold
         * the escaped string.
         */
        StringBuilder result = new StringBuilder((int) (body.length() * 1.3));
        int startOfNormal = 0;
        while (start >= 0) {
            int end = body.indexOf("\"\"\"", start + 3);
            if (end < 0) {
                throw new InvalidUserDataException("Invalid block quote starting at " + start + " in:\n" + body);
            }
            result.append(body.substring(startOfNormal, start));
            result.append('"');
            result.append(body.substring(start + 3, end).replace("\"", "\\\"").replace("\n", "\\n"));
            result.append('"');
            startOfNormal = end + 3;
            start = body.indexOf("\"\"\"", startOfNormal);
        }
        result.append(body.substring(startOfNormal));
        return result.toString();
    }

    private class TestBuilder {
        /**
         * These languages aren't supported by the syntax highlighter so we
         * shouldn't use them.
         */
        private static final List BAD_LANGUAGES = List.of("json", "javascript");

        String method = "(?<method>GET|PUT|POST|HEAD|OPTIONS|DELETE)";
        String pathAndQuery = "(?<pathAndQuery>[^\\n]+)";

        String badBody = "GET|PUT|POST|HEAD|OPTIONS|DELETE|startyaml|#";
        String body = "(?<body>(?:\\n(?!" + badBody + ")[^\\n]+)+)";

        String rawRequest = "(?:" + method + "\\s+" + pathAndQuery + body + "?)";

        String yamlRequest = "(?:startyaml(?s)(?<yaml>.+?)(?-s)endyaml)";
        String nonComment = "(?:" + rawRequest + "|" + yamlRequest + ")";
        String comment = "(?<comment>#.+)";

        String SYNTAX = "(?:" + comment + "|" + nonComment + ")\\n+";

        /**
         * Files containing all snippets that *probably* should be converted
         * to `// CONSOLE` but have yet to be converted. All files are paths
         * relative to the docs dir.
         */
        private Set<String> unconvertedCandidates = new HashSet<>();

        /**
         * The last non-TESTRESPONSE snippet.
         */
        Snippet previousTest;

        /**
         * The file in which we saw the last snippet that made a test.
         */
        Path lastDocsPath;

        /**
         * The file we're building.
         */
        PrintWriter current;

        Set<String> names = new HashSet<>();

        /**
         * Called each time a snippet is encountered. Tracks the snippets and
         * calls buildTest to actually build the test.
         */
        public void handleSnippet(Snippet snippet) {
            if (snippet.isConsoleCandidate()) {
                unconvertedCandidates.add(snippet.path().toString().replace('\\', '/'));
            }
            if (BAD_LANGUAGES.contains(snippet.language())) {
                throw new InvalidUserDataException(snippet + ": Use `js` instead of `" + snippet.language() + "`.");
            }
            if (snippet.testSetup()) {
                testSetup(snippet);
                previousTest = snippet;
                return;
            }
            if (snippet.testTearDown()) {
                testTearDown(snippet);
                previousTest = snippet;
                return;
            }
            if (snippet.testResponse() || snippet.language().equals("console-result")) {
                if (previousTest == null) {
                    throw new InvalidUserDataException(snippet + ": No paired previous test");
                }
                if (previousTest.path().equals(snippet.path()) == false) {
                    throw new InvalidUserDataException(snippet + ": Result can't be first in file");
                }
                response(snippet);
                return;
            }
            if (("js".equals(snippet.language())) && snippet.console() != null && snippet.console()) {
                throw new InvalidUserDataException(snippet + ": Use `[source,console]` instead of `// CONSOLE`.");
            }
            if (snippet.test() || snippet.language().equals("console")) {
                test(snippet);
                previousTest = snippet;
            }
            // Must be an unmarked snippet....
        }

        private void test(Snippet test) {
            setupCurrent(test);

            if (test.continued()) {
                /* Catch some difficult to debug errors with // TEST[continued]
                 * and throw a helpful error message. */
                if (previousTest == null || previousTest.path().equals(test.path()) == false) {
                    throw new InvalidUserDataException("// TEST[continued] " + "cannot be on first snippet in a file: " + test);
                }
                if (previousTest != null && previousTest.testSetup()) {
                    throw new InvalidUserDataException("// TEST[continued] " + "cannot immediately follow // TESTSETUP: " + test);
                }
                if (previousTest != null && previousTest.testSetup()) {
                    throw new InvalidUserDataException("// TEST[continued] " + "cannot immediately follow // TEARDOWN: " + test);
                }
            } else {
                current.println("---");
                if (test.name() != null && test.name().isBlank() == false) {
                    if (names.add(test.name()) == false) {
                        throw new InvalidUserDataException("Duplicated snippet name '" + test.name() + "': " + test);
                    }
                    current.println("\"" + test.name() + "\":");
                } else {
                    current.println("\"line_" + test.start() + "\":");
                }
                /* The Elasticsearch test runner doesn't support quite a few
                 * constructs unless we output this skip. We don't know if
                 * we're going to use these constructs, but we might so we
                 * output the skip just in case. */
                current.println("  - skip:");
                current.println("      features:");
                current.println("        - default_shards");
                current.println("        - stash_in_key");
                current.println("        - stash_in_path");
                current.println("        - stash_path_replace");
                current.println("        - warnings");
            }
            if (test.skip() != null) {
                if (test.continued()) {
                    throw new InvalidUserDataException("Continued snippets " + "can't be skipped");
                }
                current.println("        - always_skip");
                current.println("      reason: " + test.skip());
            }
            if (test.setup() != null) {
                setup(test);
            }

            body(test, false);

            if (test.teardown() != null) {
                teardown(test);
            }
        }

        private void response(Snippet response) {
            if (null == response.skip()) {
                current.println("  - match:");
                current.println("      $body:");
                replaceBlockQuote(response.contents()).lines().forEach(line -> current.println("        " + line));
            }
        }

        private void teardown(final Snippet snippet) {
            // insert a teardown defined outside of the docs
            for (final String name : snippet.teardown().split(",")) {
                final String teardown = getTeardowns().get().get(name);
                if (teardown == null) {
                    throw new InvalidUserDataException("Couldn't find named teardown $name for " + snippet);
                }
                current.println("# Named teardown " + name);
                current.println(teardown);
            }
        }

        private void testTearDown(Snippet snippet) {
            if (previousTest != null && previousTest.testSetup() == false && lastDocsPath.equals(snippet.path())) {
                throw new InvalidUserDataException(snippet + " must follow test setup or be first");
            }
            setupCurrent(snippet);
            current.println("---");
            current.println("teardown:");
            body(snippet, true);
        }

        void emitDo(
            String method,
            String pathAndQuery,
            String body,
            String catchPart,
            List<String> warnings,
            boolean inSetup,
            boolean skipShardFailures
        ) {
            String[] tokenized = pathAndQuery.split("\\?");
            String path = tokenized[0];
            String query = tokenized.length > 1 ? tokenized[1] : null;
            if (path == null) {
                path = ""; // Catch requests to the root...
            } else {
                path = path.replace("<", "%3C").replace(">", "%3E");
            }
            current.println("  - do:");
            if (catchPart != null) {
                current.println("      catch: " + catchPart);
            }
            if (false == warnings.isEmpty()) {
                current.println("      warnings:");
                for (String warning : warnings) {
                    // Escape " because we're going to quote the warning
                    String escaped = warning.replaceAll("\"", "\\\\\"");
                    /* Quote the warning in case it starts with [ which makes
                     * it look too much like an array. */
                    current.println("         - \"" + escaped + "\"");
                }
            }
            current.println("      raw:");
            current.println("        method: " + method);
            current.println("        path: \"" + path + "\"");
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] tokenizedQuery = param.split("=");
                    String paramName = tokenizedQuery[0];
                    String paramValue = tokenizedQuery.length > 1 ? tokenizedQuery[1] : null;
                    if (paramValue == null) {
                        paramValue = "";
                    }
                    current.println("        " + paramName + ": \"" + paramValue + "\"");
                }
            }
            if (body != null) {
                // Throw out the leading newline we get from parsing the body
                body = body.substring(1);
                // Replace """ quoted strings with valid json ones
                body = replaceBlockQuote(body);
                current.println("        body: |");
                body.lines().forEach(line -> current.println("          " + line));
            }
            /* Catch any shard failures. These only cause a non-200 response if
             * no shard succeeds. But we need to fail the tests on all of these
             * because they mean invalid syntax or broken queries or something
             * else that we don't want to teach people to do. The REST test
             * framework doesn't allow us to have assertions in the setup
             * section so we have to skip it there. We also omit the assertion
             * from APIs that don't return a JSON object
             */
            if (false == inSetup && skipShardFailures == false && shouldAddShardFailureCheck(path)) {
                current.println("  - is_false: _shards.failures");
            }
        }

        private void body(Snippet snippet, boolean inSetup) {
            ParsingUtils.parse(snippet.contents(), SYNTAX, (matcher, last) -> {
                if (matcher.group("comment") != null) {
                    // Comment
                    return;
                }
                String yamlRequest = matcher.group("yaml");
                if (yamlRequest != null) {
                    current.println(yamlRequest);
                    return;
                }
                String method = matcher.group("method");
                String pathAndQuery = matcher.group("pathAndQuery");
                String body = matcher.group("body");
                String catchPart = last ? snippet.catchPart() : null;
                if (pathAndQuery.startsWith("/")) {
                    // Leading '/'s break the generated paths
                    pathAndQuery = pathAndQuery.substring(1);
                }
                emitDo(method, pathAndQuery, body, catchPart, snippet.warnings(), inSetup, snippet.skipShardsFailures());
            });
        }

        private PrintWriter setupCurrent(Snippet test) {
            if (test.path().equals(lastDocsPath)) {
                return current;
            }
            names.clear();
            finishLastTest();
            lastDocsPath = test.path();

            // Make the destination file:
            // Shift the path into the destination directory tree
            Path dest = getOutputRoot().toPath().resolve(test.path());
            // Replace the extension
            String fileName = dest.getName(dest.getNameCount() - 1).toString();
            if (hasMultipleDocImplementations(test.path())) {
                String fileNameWithoutExt = dest.getName(dest.getNameCount() - 1).toString().replace(".asciidoc", "").replace(".mdx", "");

                if (getMigrationMode().get() == false) {
                    throw new InvalidUserDataException(
                        "Found multiple files with the same name '" + fileNameWithoutExt + "' but different extensions: [asciidoc, mdx]"
                    );
                }
                getLogger().warn("Found multiple doc file types for " + test.path() + ". Generating tests for all of them.");
                dest = dest.getParent().resolve(fileName + ".yml");

            } else {
                dest = dest.getParent().resolve(fileName.replace(".asciidoc", ".yml").replace(".mdx", ".yml"));

            }

            // Now setup the writer
            try {
                Files.createDirectories(dest.getParent());
                current = new PrintWriter(dest.toFile(), "UTF-8");
                return current;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void testSetup(Snippet snippet) {
            if (lastDocsPath == snippet.path()) {
                throw new InvalidUserDataException(
                    snippet + ": wasn't first. TESTSETUP can only be used in the first snippet of a document."
                );
            }
            setupCurrent(snippet);
            current.println("---");
            current.println("setup:");
            if (snippet.setup() != null) {
                setup(snippet);
            }
            body(snippet, true);
        }

        private void setup(final Snippet snippet) {
            // insert a setup defined outside of the docs
            for (final String name : snippet.setup().split(",")) {
                final String setup = getSetups().get(name);
                if (setup == null) {
                    throw new InvalidUserDataException("Couldn't find named setup " + name + " for " + snippet);
                }
                current.println("# Named setup " + name);
                current.println(setup);
            }
        }

        public void checkUnconverted() {
            List<String> listedButNotFound = new ArrayList<>();
            for (String listed : getExpectedUnconvertedCandidates().get()) {
                if (false == unconvertedCandidates.remove(listed)) {
                    listedButNotFound.add(listed);
                }
            }
            String message = "";
            if (false == listedButNotFound.isEmpty()) {
                Collections.sort(listedButNotFound);
                listedButNotFound = listedButNotFound.stream().map(notfound -> "    " + notfound).collect(Collectors.toList());
                message += "Expected unconverted snippets but none found in:\n";
                message += listedButNotFound.stream().collect(Collectors.joining("\n"));
            }
            if (false == unconvertedCandidates.isEmpty()) {
                List<String> foundButNotListed = new ArrayList<>(unconvertedCandidates);
                Collections.sort(foundButNotListed);
                foundButNotListed = foundButNotListed.stream().map(f -> "    " + f).collect(Collectors.toList());
                if (false == "".equals(message)) {
                    message += "\n";
                }
                message += "Unexpected unconverted snippets:\n";
                message += foundButNotListed.stream().collect(Collectors.joining("\n"));
            }
            if (false == "".equals(message)) {
                throw new InvalidUserDataException(message);
            }
        }

        public void finishLastTest() {
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }

    private void assertEqualTestSnippetFromMigratedDocs() {
        getTestRoot().getAsFileTree().matching(patternSet -> { patternSet.include("**/*asciidoc.yml"); }).forEach(asciidocFile -> {
            File mdxFile = new File(asciidocFile.getAbsolutePath().replace(".asciidoc.yml", ".mdx.yml"));
            if (mdxFile.exists() == false) {
                throw new InvalidUserDataException("Couldn't find the corresponding mdx file for " + asciidocFile.getAbsolutePath());
            }
            try {
                List<String> asciidocLines = Files.readAllLines(asciidocFile.toPath());
                List<String> mdxLines = Files.readAllLines(mdxFile.toPath());
                if (asciidocLines.size() != mdxLines.size()) {
                    throw new GradleException(
                        "Yaml rest specs ("
                            + asciidocFile.toPath()
                            + " and "
                            + mdxFile.getAbsolutePath()
                            + ") are not equal, different line count"
                    );

                }
                for (int i = 0; i < asciidocLines.size(); i++) {
                    if (asciidocLines.get(i)
                        .replaceAll("line_\\d+", "line_0")
                        .equals(mdxLines.get(i).replaceAll("line_\\d+", "line_0")) == false) {
                        throw new GradleException(
                            "Yaml rest specs ("
                                + asciidocFile.toPath()
                                + " and "
                                + mdxFile.getAbsolutePath()
                                + ") are not equal, difference on line: "
                                + (i + 1)
                        );
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean hasMultipleDocImplementations(Path path) {
        File dir = getDocs().getDir();
        String fileName = path.getName(path.getNameCount() - 1).toString();
        if (fileName.endsWith("asciidoc")) {
            return new File(dir, path.toString().replace(".asciidoc", ".mdx")).exists();
        } else if (fileName.endsWith("mdx")) {
            return new File(dir, path.toString().replace(".mdx", ".asciidoc")).exists();
        }
        return false;
    }

}
