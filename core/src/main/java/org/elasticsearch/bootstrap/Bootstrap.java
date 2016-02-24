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

package org.elasticsearch.bootstrap;

import org.apache.lucene.util.Constants;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.common.PidFile;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.inject.CreationException;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.logging.log4j.LogConfigurator;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.os.OsProbe;
import org.elasticsearch.monitor.process.ProcessProbe;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.transport.TransportSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;



/**
 * Internal startup code.
 */
final class Bootstrap {

    private static volatile Bootstrap INSTANCE;
    private volatile Node node;
    private final CountDownLatch keepAliveLatch = new CountDownLatch(1);
    private final Thread keepAliveThread;

    /** creates a new instance */
    Bootstrap() {
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    keepAliveLatch.await();
                } catch (InterruptedException e) {
                    // bail out
                }
            }
        }, "elasticsearch[keepAlive/" + Version.CURRENT + "]");
        keepAliveThread.setDaemon(false);
        // keep this thread alive (non daemon thread) until we shutdown
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                keepAliveLatch.countDown();
            }
        });
    }

    /** initialize native resources */
    public static void initializeNatives(Path tmpFile, boolean mlockAll, boolean seccomp, boolean ctrlHandler) {
        final ESLogger logger = Loggers.getLogger(Bootstrap.class);

        // check if the user is running as root, and bail
        if (Natives.definitelyRunningAsRoot()) {
            if (Boolean.parseBoolean(System.getProperty("es.insecure.allow.root"))) {
                logger.warn("running as ROOT user. this is a bad idea!");
            } else {
                throw new RuntimeException("don't run elasticsearch as root.");
            }
        }

        // enable secure computing mode
        if (seccomp) {
            Natives.trySeccomp(tmpFile);
        }

        // mlockall if requested
        if (mlockAll) {
            if (Constants.WINDOWS) {
               Natives.tryVirtualLock();
            } else {
               Natives.tryMlockall();
            }
        }

        // listener for windows close event
        if (ctrlHandler) {
            Natives.addConsoleCtrlHandler(new ConsoleCtrlHandler() {
                @Override
                public boolean handle(int code) {
                    if (CTRL_CLOSE_EVENT == code) {
                        logger.info("running graceful exit on windows");
                        try {
                            Bootstrap.stop();
                        } catch (IOException e) {
                            throw new ElasticsearchException("failed to stop node", e);
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        // force remainder of JNA to be loaded (if available).
        try {
            JNAKernel32Library.getInstance();
        } catch (Throwable ignored) {
            // we've already logged this.
        }

        // init lucene random seed. it will use /dev/urandom where available:
        StringHelper.randomId();
    }

    static void initializeProbes() {
        // Force probes to be loaded
        ProcessProbe.getInstance();
        OsProbe.getInstance();
        JvmInfo.jvmInfo();
    }

    private void setup(boolean addShutdownHook, Settings settings, Environment environment) throws Exception {
        initializeNatives(
                environment.tmpFile(),
                BootstrapSettings.MLOCKALL_SETTING.get(settings),
                BootstrapSettings.SECCOMP_SETTING.get(settings),
                BootstrapSettings.CTRLHANDLER_SETTING.get(settings));

        // initialize probes before the security manager is installed
        initializeProbes();

        if (addShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        IOUtils.close(node);
                    } catch (IOException ex) {
                        throw new ElasticsearchException("failed to stop node", ex);
                    }
                }
            });
        }

        // look for jar hell
        JarHell.checkJarHell();

        // install SM after natives, shutdown hooks, etc.
        Security.configure(environment, BootstrapSettings.SECURITY_FILTER_BAD_DEFAULTS_SETTING.get(settings));

        // We do not need to reload system properties here as we have already applied them in building the settings and
        // reloading could cause multiple prompts to the user for values if a system property was specified with a prompt
        // placeholder
        Settings nodeSettings = Settings.settingsBuilder()
                .put(settings)
                .put(InternalSettingsPreparer.IGNORE_SYSTEM_PROPERTIES_SETTING.getKey(), true)
                .build();
        enforceOrLogLimits(nodeSettings);

        node = new Node(nodeSettings);
    }

    @SuppressForbidden(reason = "Exception#printStackTrace()")
    private static void setupLogging(Settings settings) {
        try {
            Class.forName("org.apache.log4j.Logger");
            LogConfigurator.configure(settings, true);
        } catch (ClassNotFoundException e) {
            // no log4j
        } catch (NoClassDefFoundError e) {
            // no log4j
        } catch (Exception e) {
            sysError("Failed to configure logging...", false);
            e.printStackTrace();
        }
    }
    private static Environment initialSettings(boolean daemonize, String pathHome, String pidFile) {
        Terminal terminal = daemonize ? null : Terminal.DEFAULT;
        Settings.Builder builder = Settings.builder();
        builder.put(Environment.PATH_HOME_SETTING.getKey(), pathHome);
        if (Strings.hasLength(pidFile)) {
            builder.put(Environment.PIDFILE_SETTING.getKey(), pidFile);
        }
        return InternalSettingsPreparer.prepareEnvironment(builder.build(), terminal);
    }

    private void start() {
        node.start();
        keepAliveThread.start();
    }

    static void stop() throws IOException {
        try {
            IOUtils.close(INSTANCE.node);
        } finally {
            INSTANCE.keepAliveLatch.countDown();
        }
    }

    /** Set the system property before anything has a chance to trigger its use */
    // TODO: why? is it just a bad default somewhere? or is it some BS around 'but the client' garbage <-- my guess
    @SuppressForbidden(reason = "sets logger prefix on initialization")
    static void initLoggerPrefix() {
        System.setProperty("es.logger.prefix", "");
    }

    /**
     * This method is invoked by {@link Elasticsearch#main(String[])}
     * to startup elasticsearch.
     */
    static void init(boolean daemonize, String pathHome, String pidFile) throws Throwable {
        // Set the system property before anything has a chance to trigger its use
        initLoggerPrefix();

        INSTANCE = new Bootstrap();

        Environment environment = initialSettings(daemonize, pathHome, pidFile);
        Settings settings = environment.settings();
        setupLogging(settings);
        checkForCustomConfFile();

        if (environment.pidFile() != null) {
            PidFile.create(environment.pidFile(), true);
        }

        // warn if running using the client VM
        if (JvmInfo.jvmInfo().getVmName().toLowerCase(Locale.ROOT).contains("client")) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            logger.warn("jvm uses the client vm, make sure to run `java` with the server vm for best performance by adding `-server` to the command line");
        }

        try {
            if (daemonize) {
                Loggers.disableConsoleLogging();
                closeSystOut();
            }

            // fail if using broken version
            JVMCheck.check();

            // fail if somebody replaced the lucene jars
            checkLucene();

            INSTANCE.setup(true, settings, environment);

            INSTANCE.start();

            if (daemonize) {
                closeSysError();
            }
        } catch (Throwable e) {
            // disable console logging, so user does not see the exception twice (jvm will show it already)
            if (!daemonize) {
                Loggers.disableConsoleLogging();
            }
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            if (INSTANCE.node != null) {
                logger = Loggers.getLogger(Bootstrap.class, INSTANCE.node.settings().get("node.name"));
            }
            // HACK, it sucks to do this, but we will run users out of disk space otherwise
            if (e instanceof CreationException) {
                // guice: log the shortened exc to the log file
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(os, false, "UTF-8");
                new StartupError(e).printStackTrace(ps);
                ps.flush();
                logger.error("Guice Exception: {}", os.toString("UTF-8"));
            } else {
                // full exception
                logger.error("Exception", e);
            }
            // re-enable it if appropriate, so they can see any logging during the shutdown process
            if (!daemonize) {
                Loggers.enableConsoleLogging();
            }

            throw e;
        }
    }

    @SuppressForbidden(reason = "System#out")
    private static void closeSystOut() {
        System.out.close();
    }

    @SuppressForbidden(reason = "System#err")
    private static void closeSysError() {
        System.err.close();
    }

    @SuppressForbidden(reason = "System#err")
    private static void sysError(String line, boolean flush) {
        System.err.println(line);
        if (flush) {
            System.err.flush();
        }
    }

    private static void checkForCustomConfFile() {
        String confFileSetting = System.getProperty("es.default.config");
        checkUnsetAndMaybeExit(confFileSetting, "es.default.config");
        confFileSetting = System.getProperty("es.config");
        checkUnsetAndMaybeExit(confFileSetting, "es.config");
        confFileSetting = System.getProperty("elasticsearch.config");
        checkUnsetAndMaybeExit(confFileSetting, "elasticsearch.config");
    }

    private static void checkUnsetAndMaybeExit(String confFileSetting, String settingName) {
        if (confFileSetting != null && confFileSetting.isEmpty() == false) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            logger.info("{} is no longer supported. elasticsearch.yml must be placed in the config directory and cannot be renamed.", settingName);
            exit(1);
        }
    }

    @SuppressForbidden(reason = "Allowed to exit explicitly in bootstrap phase")
    private static void exit(int status) {
        System.exit(status);
    }

    private static void checkLucene() {
        if (Version.CURRENT.luceneVersion.equals(org.apache.lucene.util.Version.LATEST) == false) {
            throw new AssertionError("Lucene version mismatch this version of Elasticsearch requires lucene version ["
                + Version.CURRENT.luceneVersion + "]  but the current lucene version is [" + org.apache.lucene.util.Version.LATEST + "]");
        }
    }

    static final Set<Setting> ENFORCE_SETTINGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        TransportSettings.BIND_HOST,
        TransportSettings.HOST,
        TransportSettings.PUBLISH_HOST,
        NetworkService.GLOBAL_NETWORK_HOST_SETTING,
        NetworkService.GLOBAL_NETWORK_BINDHOST_SETTING,
        NetworkService.GLOBAL_NETWORK_PUBLISHHOST_SETTING
    )));

    private static boolean enforceLimits(Settings settings) {
        for (Setting setting : ENFORCE_SETTINGS) {
            if (setting.exists(settings)) {
                return true;
            }
        }
        return false;
    }

    static void enforceOrLogLimits(Settings settings) { // pkg private for testing
        /* We enforce limits once any network host is configured. In this case we assume the node is running in production
         * and all production limit checks must pass. This should be extended as we go to settings like:
         *   - discovery.zen.minimum_master_nodes
         *   - discovery.zen.ping.unicast.hosts is set if we use zen disco
         *   - ensure we can write in all data directories
         *   - fail if mlockall failed and was configured
         *   - fail if vm.max_map_count is under a certain limit (not sure if this works cross platform)
         *   - fail if the default cluster.name is used, if this is setup on network a real clustername should be used?*/
        final boolean enforceLimits = enforceLimits(settings);
        final ESLogger logger = Loggers.getLogger(Bootstrap.class);
        final long maxFileDescriptorCount = ProcessProbe.getInstance().getMaxFileDescriptorCount();
        if (maxFileDescriptorCount != -1) {
            final int fileDescriptorCountThreshold = (1 << 16);
            if (maxFileDescriptorCount < fileDescriptorCountThreshold) {
                if (enforceLimits){
                    throw new IllegalStateException("max file descriptors [" + maxFileDescriptorCount
                        + "] for elasticsearch process likely too low, increase it to at least [" + fileDescriptorCountThreshold +"]");
                }
                logger.warn(
                    "max file descriptors [{}] for elasticsearch process likely too low, consider increasing to at least [{}]",
                    maxFileDescriptorCount, fileDescriptorCountThreshold);
            }
        }
    }
}
