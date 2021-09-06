/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.cli;

import joptsimple.OptionSet;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.util.SetOnce;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.cluster.coordination.ClusterBootstrapService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.KeyStoreWrapper;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.SettingsBasedSeedHostsProvider;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.HttpTransportSettings;
import org.elasticsearch.node.Node;
import org.elasticsearch.xpack.core.XPackSettings;

import javax.security.auth.x500.X500Principal;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.elasticsearch.xpack.security.cli.CertGenUtils.buildDnFromDomain;

/**
 * Configures a new cluster node, by appending to the elasticsearch.yml, so that it forms a single node cluster with
 * Security enabled. Used to configure only the initial node of a cluster, and only before the first time that the node
 * is started. Subsequent nodes can be added to the cluster via the enrollment flow, but this is not used to
 * configure such nodes or to display the necessary configuration (ie the enrollment tokens) for such.
 *
 * This will NOT run if Security is explicitly configured or if the existing configuration otherwise clashes with the
 * intent of this (i.e. the node is configured so it might not form a single node cluster).
 */
public final class ConfigInitialNode extends EnvironmentAwareCommand {

    public static final String AUTO_CONFIG_ALT_DN = "CN=Elasticsearch security auto-configuration HTTP CA";
    // the transport keystore is also used as a truststore
    private static final String TRANSPORT_AUTOGENERATED_KEYSTORE_NAME = "transport_keystore_all_nodes";
    private static final String TRANSPORT_AUTOGENERATED_KEY_ALIAS = "transport_all_nodes_key";
    private static final String TRANSPORT_AUTOGENERATED_CERT_ALIAS = "transport_all_nodes_cert";
    private static final int TRANSPORT_CERTIFICATE_DAYS = 99 * 365;
    private static final int TRANSPORT_KEY_SIZE = 4096;
    private static final String HTTP_AUTOGENERATED_KEYSTORE_NAME = "http_keystore_local_node";
    private static final String HTTP_AUTOGENERATED_CA_NAME = "http_ca";
    private static final int HTTP_CA_CERTIFICATE_DAYS = 3 * 365;
    private static final int HTTP_CA_KEY_SIZE = 4096;
    private static final int HTTP_CERTIFICATE_DAYS = 2 * 365;
    private static final int HTTP_KEY_SIZE = 4096;
    private static final String TLS_CONFIG_DIR_NAME_PREFIX = "tls_auto_config_initial_node_";

    public ConfigInitialNode() {
        super("Generates all the necessary security configuration for the initial node of a new secure cluster");
        // This "cli utility" must be invoked EXCLUSIVELY from the node startup script, where it is passed all the
        // node startup options unfiltered. It cannot consume most of them, but it does need to inspect the `-E` ones.
        parser.allowsUnrecognizedOptions();
    }

    public static void main(String[] args) throws Exception {
        exit(new ConfigInitialNode().main(args, Terminal.DEFAULT));
    }

    private boolean isInitialNodeAutoConfigured(Environment environment) {
        return XPackSettings.SECURITY_ENABLED.get(environment.settings()) &&
                XPackSettings.ENROLLMENT_ENABLED.get(environment.settings()) &&
                isInitialClusterNode(environment.settings()) &&
                DiscoveryNode.isMasterNode(environment.settings()) &&
                false == DiscoveryNode.hasRole(environment.settings(), DiscoveryNodeRole.VOTING_ONLY_NODE_ROLE) &&
                DiscoveryNode.canContainData(environment.settings()) &&
                usesTransportTLSWithAutoGeneratedCerts(environment);
    }

    @Override
    protected void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
        // Silently skipping security auto configuration because node considered as restarting.
        if (Files.isDirectory(env.dataFile()) && Files.list(env.dataFile()).findAny().isPresent()) {
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because it appears that the node is not starting up for the first time.");
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "The node might already be part of a cluster and this auto setup utility is designed to configure Security for new " +
                            "clusters only.");
            // we wish the node to start as usual during a restart
            // but still the exit code should indicate that this has not been run
            throw new UserException(ExitCodes.NOOP, null);
        }

        // pre-flight checks for the files that are going to be changed
        final Path ymlPath = env.configFile().resolve("elasticsearch.yml");
        final Path keystorePath = KeyStoreWrapper.keystorePath(env.configFile());
        // it is odd for the `elasticsearch.yml` file to be missing or not be a regular (the node won't start)
        // but auto configuration should not be concerned with fixing it (by creating the file) and let the node startup fail
        if (false == Files.exists(ymlPath) || false == Files.isRegularFile(ymlPath, LinkOption.NOFOLLOW_LINKS)) {
            terminal.println(Terminal.Verbosity.NORMAL, String.format(Locale.ROOT, "Skipping security auto configuration because" +
                    " the configuration file [%s] is missing or is not a regular file", ymlPath));
            throw new UserException(ExitCodes.CONFIG, null);
        }
        // If the node's yml configuration is not readable, most probably auto-configuration isn't run under the suitable user
        if (false == Files.isReadable(ymlPath)) {
            terminal.println(Terminal.Verbosity.NORMAL, String.format(Locale.ROOT, "Skipping security auto configuration because" +
                    " the configuration file [%s] is not readable", ymlPath));
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Inform that auto-configuration will not run if keystore cannot be read.
        if (Files.exists(keystorePath) && (false == Files.isRegularFile(keystorePath, LinkOption.NOFOLLOW_LINKS) ||
                false == Files.isReadable(keystorePath))) {
            terminal.println(Terminal.Verbosity.NORMAL, String.format(Locale.ROOT, "Skipping security auto configuration because" +
                    " the node keystore file [%s] is not a readable regular file", keystorePath));
            throw new UserException(ExitCodes.NOOP, null);
        }

        // only perform auto-configuration if the existing configuration is not conflicting (eg Security already enabled)
        // if it is, silently skip auto configuration
        checkExistingConfiguration(env.settings(), terminal);

        final ZonedDateTime autoConfigDate = ZonedDateTime.now(ZoneOffset.UTC);
        final String instantAutoConfigName = TLS_CONFIG_DIR_NAME_PREFIX + autoConfigDate.toInstant().getEpochSecond();
        final Path instantAutoConfigDir = env.configFile().resolve(instantAutoConfigName);
        try {
            // it is useful to pre-create the sub-config dir in order to check that the config dir is writable and that file owners match
            Files.createDirectory(instantAutoConfigDir);
            // set permissions to 750, don't rely on umask, we assume auto configuration preserves ownership so we don't have to
            // grant "group" or "other" permissions
            PosixFileAttributeView view = Files.getFileAttributeView(instantAutoConfigDir, PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(PosixFilePermissions.fromString("rwxr-x---"));
            }
        } catch (Exception e) {
            try {
                deleteDirectory(instantAutoConfigDir);
            } catch (Exception ex) {
                e.addSuppressed(ex);
            }
            // the config dir is probably read-only, either because this auto-configuration runs as a different user from the install user,
            // or if the admin explicitly makes configuration immutable (read-only), both of which are reasons to skip auto-configuration
            // this will show a message to the console (the first time the node starts) and auto-configuration is effectively bypassed
            // the message will not be subsequently shown (because auto-configuration doesn't run for node restarts)
            throw new UserException(ExitCodes.CANT_CREATE, "Could not create auto configuration directory", e);
        }

        // Check that the created auto-config dir has the same owner as the config dir.
        // This is a sort of sanity check.
        // If the node process works OK given the owner of the config dir, it should also tolerate the auto-created config dir,
        // provided that they both have the same owner and permissions.
        final UserPrincipal newFileOwner = Files.getOwner(instantAutoConfigDir, LinkOption.NOFOLLOW_LINKS);
        if (false == newFileOwner.equals(Files.getOwner(env.configFile(), LinkOption.NOFOLLOW_LINKS))) {
            deleteDirectory(instantAutoConfigDir);
            // the following is only printed once, if the node starts successfully
            throw new UserException(ExitCodes.CONFIG, "Aborting auto configuration because of config dir ownership mismatch");
        }
        // TODO: Guard with a try-catch and clean the empty auto-config directory if we fail to generate any of the keys/certificates ?

        // the transport key-pair is the same across the cluster and is trusted without hostname verification (it is self-signed),
        final X500Principal certificatePrincipal = new X500Principal(buildDnFromDomain(System.getenv("HOSTNAME")));
        final X500Principal caPrincipal = new X500Principal(AUTO_CONFIG_ALT_DN);
        // this does DNS resolve and could block
        final GeneralNames subjectAltNames = getSubjectAltNames();

        KeyPair transportKeyPair = CertGenUtils.generateKeyPair(TRANSPORT_KEY_SIZE);
        // self-signed which is not a CA
        X509Certificate transportCert = CertGenUtils.generateSignedCertificate(certificatePrincipal,
                subjectAltNames, transportKeyPair, null, null, false, TRANSPORT_CERTIFICATE_DAYS, "SHA256withRSA");
        KeyPair httpCAKeyPair = CertGenUtils.generateKeyPair(HTTP_CA_KEY_SIZE);
        // self-signed CA
        X509Certificate httpCACert = CertGenUtils.generateSignedCertificate(caPrincipal,
               null , httpCAKeyPair, null, null, true, HTTP_CA_CERTIFICATE_DAYS, "SHA256withRSA");
        KeyPair httpKeyPair = CertGenUtils.generateKeyPair(HTTP_KEY_SIZE);
        // non-CA
        X509Certificate httpCert = CertGenUtils.generateSignedCertificate(certificatePrincipal,
                subjectAltNames, httpKeyPair, httpCACert, httpCAKeyPair.getPrivate(), false, HTTP_CERTIFICATE_DAYS, "SHA256withRSA");

        // the HTTP CA PEM file is provided "just in case". The node doesn't use it, but clients (configured manually, outside of the
        // enrollment process) might indeed need it, and it is currently impossible to retrieve it
        try {
            fullyWriteFile(instantAutoConfigDir, HTTP_AUTOGENERATED_CA_NAME + ".crt", false, stream -> {
                try (JcaPEMWriter pemWriter =
                             new JcaPEMWriter(new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8)))) {
                    pemWriter.writeObject(httpCACert);
                }
            });
        } catch (Exception e) {
            deleteDirectory(instantAutoConfigDir);
            // this is an error which mustn't be ignored during node startup
            // the exit code for unhandled Exceptions is "1"
            throw e;
        }

        // save original keystore before updating (replacing)
        final Path keystoreBackupPath =
                env.configFile().resolve(KeyStoreWrapper.KEYSTORE_FILENAME + "." + autoConfigDate.toInstant().getEpochSecond() + ".orig");
        if (Files.exists(keystorePath)) {
            try {
                Files.copy(keystorePath, keystoreBackupPath, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (Exception e) {
                try {
                    deleteDirectory(instantAutoConfigDir);
                } catch (Exception ex) {
                    e.addSuppressed(ex);
                }
                throw e;
            }
        }

        final SetOnce<SecureString> nodeKeystorePassword = new SetOnce<>();
        try (KeyStoreWrapper nodeKeystore = KeyStoreWrapper.bootstrap(env.configFile(), () -> {
            nodeKeystorePassword.set(new SecureString(terminal.readSecret("",
                    KeyStoreWrapper.MAX_PASSPHRASE_LENGTH)));
            return nodeKeystorePassword.get().clone();
        })) {
            // do not overwrite keystore entries
            // instead expect the user to manually remove them herself
            if (nodeKeystore.getSettingNames().contains("xpack.security.transport.ssl.keystore.secure_password") ||
                nodeKeystore.getSettingNames().contains("xpack.security.transport.ssl.truststore.secure_password") ||
                nodeKeystore.getSettingNames().contains("xpack.security.http.ssl.keystore.secure_password")) {
                // this error condition is akin to condition of existing configuration in the yml file
                // this is not a fresh install and the admin has something planned for Security
                // Even though this is probably invalid configuration, do NOT fix it, let the node fail to start in its usual way.
                // Still display a message, because this can be tricky to figure out (why auto-conf did not run) if by mistake.
                throw new UserException(ExitCodes.CONFIG, "Aborting auto configuration because the node keystore contains password " +
                        "settings already");
            }
            try (SecureString transportKeystorePassword = newKeystorePassword()) {
                KeyStore transportKeystore = KeyStore.getInstance("PKCS12");
                transportKeystore.load(null);
                // the PKCS12 keystore and the contained private key use the same password
                transportKeystore.setKeyEntry(TRANSPORT_AUTOGENERATED_KEY_ALIAS, transportKeyPair.getPrivate(),
                        transportKeystorePassword.getChars(), new Certificate[]{transportCert});
                // the transport keystore is used as a trustore too, hence it must contain a certificate entry
                transportKeystore.setCertificateEntry(TRANSPORT_AUTOGENERATED_CERT_ALIAS, transportCert);
                fullyWriteFile(instantAutoConfigDir, TRANSPORT_AUTOGENERATED_KEYSTORE_NAME + ".p12", false,
                        stream -> transportKeystore.store(stream, transportKeystorePassword.getChars()));
                nodeKeystore.setString("xpack.security.transport.ssl.keystore.secure_password", transportKeystorePassword.getChars());
                // we use the same PKCS12 file for the keystore and the truststore
                nodeKeystore.setString("xpack.security.transport.ssl.truststore.secure_password", transportKeystorePassword.getChars());
            }
            try (SecureString httpKeystorePassword = newKeystorePassword()) {
                KeyStore httpKeystore = KeyStore.getInstance("PKCS12");
                httpKeystore.load(null);
                // the keystore contains both the node's and the CA's private keys
                // both keys are encrypted using the same password as the PKCS12 keystore they're contained in
                httpKeystore.setKeyEntry(HTTP_AUTOGENERATED_KEYSTORE_NAME + "_ca", httpCAKeyPair.getPrivate(),
                        httpKeystorePassword.getChars(), new Certificate[]{httpCACert});
                httpKeystore.setKeyEntry(HTTP_AUTOGENERATED_KEYSTORE_NAME, httpKeyPair.getPrivate(),
                        httpKeystorePassword.getChars(), new Certificate[]{httpCert, httpCACert});
                fullyWriteFile(instantAutoConfigDir, HTTP_AUTOGENERATED_KEYSTORE_NAME + ".p12", false,
                        stream -> httpKeystore.store(stream, httpKeystorePassword.getChars()));
                nodeKeystore.setString("xpack.security.http.ssl.keystore.secure_password", httpKeystorePassword.getChars());
            }
            // finally overwrites the node keystore (if the keystores have been successfully written)
            nodeKeystore.save(env.configFile(), nodeKeystorePassword.get() == null ? new char[0] : nodeKeystorePassword.get().getChars());
        } catch (Exception e) {
            // restore keystore to revert possible keystore bootstrap
            try {
                if (Files.exists(keystoreBackupPath)) {
                    Files.move(keystoreBackupPath, keystorePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.deleteIfExists(keystorePath);
                }
            } catch (Exception ex) {
                e.addSuppressed(ex);
            }
            try {
                deleteDirectory(instantAutoConfigDir);
            } catch (Exception ex) {
                e.addSuppressed(ex);
            }
            throw e;
        } finally {
            if (nodeKeystorePassword.get() != null) {
                nodeKeystorePassword.get().close();
            }
        }

        try {
            List<String> existingConfigLines = Files.readAllLines(ymlPath, StandardCharsets.UTF_8);
            fullyWriteFile(env.configFile(), "elasticsearch.yml", true, stream -> {
                try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    // start with the existing config lines
                    for (String line : existingConfigLines) {
                        bw.write(line);
                        bw.newLine();
                    }
                    bw.newLine();
                    bw.newLine();
                    bw.write("###################################################################################");
                    bw.newLine();
                    bw.write("# The following settings, and associated TLS certificates and keys configuration, #");
                    bw.newLine();
                    bw.write("# have been automatically generated in order to configure Security.               #");
                    bw.newLine();
                    bw.write("# These have been generated the first time that the new node was started, without #");
                    bw.newLine();
                    bw.write("# joining or enrolling to an existing cluster and only if Security had not been   #");
                    bw.newLine();
                    bw.write("# explicitly configured beforehand.                                               #");
                    bw.newLine();
                    bw.write(String.format(Locale.ROOT, "# %-79s #", ""));
                    bw.newLine();
                    bw.write(String.format(Locale.ROOT, "# %-79s #", autoConfigDate));
                    // TODO add link to docs
                    bw.newLine();
                    bw.write("###################################################################################");
                    bw.newLine();
                    bw.newLine();
                    bw.write(XPackSettings.SECURITY_ENABLED.getKey() + ": true");
                    bw.newLine();
                    bw.newLine();
                    if (false == env.settings().hasValue(XPackSettings.ENROLLMENT_ENABLED.getKey())) {
                        bw.write(XPackSettings.ENROLLMENT_ENABLED.getKey() + ": true");
                        bw.newLine();
                        bw.newLine();
                    }

                    bw.write("xpack.security.transport.ssl.enabled: true");
                    bw.newLine();
                    bw.write("# All the nodes use the same key and certificate on the inter-node connection");
                    bw.newLine();
                    bw.write("xpack.security.transport.ssl.verification_mode: certificate");
                    bw.newLine();
                    bw.write("xpack.security.transport.ssl.keystore.path: " + instantAutoConfigDir
                            .resolve(TRANSPORT_AUTOGENERATED_KEYSTORE_NAME + ".p12"));
                    bw.newLine();
                    // we use the keystore as a truststore in order to minimize the number of auto-generated resources,
                    // and also because a single file is more idiomatic to the scheme of a shared secret between the cluster nodes
                    // no one should only need the TLS cert without the associated key for the transport layer
                    bw.write("xpack.security.transport.ssl.truststore.path: " + instantAutoConfigDir
                            .resolve(TRANSPORT_AUTOGENERATED_KEYSTORE_NAME + ".p12"));
                    bw.newLine();

                    bw.newLine();
                    bw.write("xpack.security.http.ssl.enabled: true");
                    bw.newLine();
                    bw.write("xpack.security.http.ssl.keystore.path: " + instantAutoConfigDir.resolve(HTTP_AUTOGENERATED_KEYSTORE_NAME +
                            ".p12"));
                    bw.newLine();

                    // we have configured TLS on the transport layer with newly generated certs and keys,
                    // hence this node cannot form a multi-node cluster
                    // if we don't set the following the node might trip the discovery bootstrap check
                    if (false == DiscoveryModule.isSingleNodeDiscovery(env.settings()) &&
                            false == ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING.exists(env.settings())) {
                        bw.newLine();
                        bw.write("# The initial node with security auto-configured must form a cluster on its own,");
                        bw.newLine();
                        bw.write("# and all the subsequent nodes should be added via the node enrollment flow");
                        bw.newLine();
                        bw.write(ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING.getKey() + ": [\"${HOSTNAME}\"]");
                        bw.newLine();
                    }

                    // if any address settings have been set, assume the admin has thought it through wrt to addresses,
                    // and don't try to be smart and mess with that
                    if (false == (env.settings().hasValue(HttpTransportSettings.SETTING_HTTP_HOST.getKey()) ||
                            env.settings().hasValue(HttpTransportSettings.SETTING_HTTP_BIND_HOST.getKey()) ||
                            env.settings().hasValue(HttpTransportSettings.SETTING_HTTP_PUBLISH_HOST.getKey()) ||
                            env.settings().hasValue(NetworkService.GLOBAL_NETWORK_HOST_SETTING.getKey()) ||
                            env.settings().hasValue(NetworkService.GLOBAL_NETWORK_BIND_HOST_SETTING.getKey()) ||
                            env.settings().hasValue(NetworkService.GLOBAL_NETWORK_PUBLISH_HOST_SETTING.getKey()))) {
                        bw.newLine();
                        bw.write("# With security now configured, which includes user authentication over HTTPs, " +
                                "it's reasonable to serve requests on the local network too");
                        bw.newLine();
                        bw.write(HttpTransportSettings.SETTING_HTTP_HOST.getKey() + ": [_local_, _site_]");
                        bw.newLine();
                    }
                }
            });
        } catch (Exception e) {
            try {
                if (Files.exists(keystoreBackupPath)) {
                    Files.move(keystoreBackupPath, keystorePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.deleteIfExists(keystorePath);
                }
            } catch (Exception ex) {
                e.addSuppressed(ex);
            }
            try {
                deleteDirectory(instantAutoConfigDir);
            } catch (Exception ex) {
                e.addSuppressed(ex);
            }
            throw e;
        }
        // only delete the backed up file if all went well
        Files.deleteIfExists(keystoreBackupPath);
        assert isInitialNodeAutoConfigured(env);
    }

    @SuppressForbidden(reason = "Uses File API because the commons io library does, which is useful for file manipulation")
    private void deleteDirectory(Path directory) throws IOException {
        FileUtils.deleteDirectory(directory.toFile());
    }

    @SuppressForbidden(reason = "DNS resolve InetAddress#getCanonicalHostName used to populate auto generated HTTPS cert")
    private GeneralNames getSubjectAltNames() throws IOException {
        Set<GeneralName> generalNameSet = new HashSet<>();
        for (InetAddress ip : NetworkUtils.getAllAddresses()) {
            String ipString = NetworkAddress.format(ip);
            generalNameSet.add(new GeneralName(GeneralName.iPAddress, ipString));
            String reverseFQDN = ip.getCanonicalHostName();
            if (false == ipString.equals(reverseFQDN)) {
                // reverse FQDN successful
                generalNameSet.add(new GeneralName(GeneralName.dNSName, reverseFQDN));
            }
        }
        return new GeneralNames(generalNameSet.toArray(new GeneralName[0]));
    }

    // for tests
    SecureString newKeystorePassword() {
        return UUIDs.randomBase64UUIDSecureString();
    }

    /*
     * Detect if the existing yml configuration is incompatible with auto-configuration,
     * in which case auto-configuration is SILENTLY skipped.
     * This assumes the user knows what they are doing when configuring the node.
     */
    void checkExistingConfiguration(Settings settings, Terminal terminal) throws UserException {
        // Allow the user to explicitly set that they don't want auto-configuration for security, regardless of our heuristics
        if (XPackSettings.SECURITY_AUTOCONFIGURATION_ENABLED.get(settings) == false) {
            terminal.println(
                Terminal.Verbosity.VERBOSE,
                "Skipping security auto configuration because [" + XPackSettings.SECURITY_AUTOCONFIGURATION_ENABLED.getKey() + "] is false"
            );
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Silently skip security auto configuration when Security is already configured.
        // Security is enabled implicitly, but if the admin chooses to enable it explicitly then
        // skip the TLS auto-configuration, as this is a sign that the admin is opting for the default behavior
        if (XPackSettings.SECURITY_ENABLED.exists(settings)) {
            // do not try to validate, correct or fill in any incomplete security configuration,
            // instead rely on the regular node startup to do this validation
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because it appears that security is already configured.");
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Silently skip security auto configuration if enrollment is disabled.
        if (XPackSettings.ENROLLMENT_ENABLED.exists(settings) && false == XPackSettings.ENROLLMENT_ENABLED.get(settings)) {
            // Tolerate enrollment explicitly ENABLED, as it could be useful to enable it by a command line option
            // only the first time that the node is started.
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because enrollment is explicitly disabled.");
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Security auto configuration must not run if the node is configured for multi-node cluster formation (bootstrap or join).
        // This is because transport TLS with newly generated certs will hinder cluster formation because the other nodes cannot trust yet.
        if (false == isInitialClusterNode(settings)) {
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because this node is configured to bootstrap or to join a " +
                            "multi-node cluster, which is not supported.");
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Silently skip security auto configuration because node cannot become master.
        boolean canBecomeMaster = DiscoveryNode.isMasterNode(settings) &&
                false == DiscoveryNode.hasRole(settings, DiscoveryNodeRole.VOTING_ONLY_NODE_ROLE);
        if (false == canBecomeMaster) {
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because the node is configured such that it cannot become master.");
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Silently skip security auto configuration, because the node cannot contain the Security index data
        boolean canHoldSecurityIndex = DiscoveryNode.canContainData(settings);
        if (false == canHoldSecurityIndex) {
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because the node is configured such that it cannot contain data.");
            throw new UserException(ExitCodes.NOOP, null);
        }
        // Silently skipping security auto configuration because TLS is already configured
        if (false == settings.getByPrefix(XPackSettings.TRANSPORT_SSL_PREFIX).isEmpty() ||
                false == settings.getByPrefix(XPackSettings.HTTP_SSL_PREFIX).isEmpty()) {
            // zero validation for the TLS settings as well, let the node boot and do its thing
            terminal.println(Terminal.Verbosity.VERBOSE,
                    "Skipping security auto configuration because it appears that TLS is already configured.");
            throw new UserException(ExitCodes.NOOP, null);
        }
        // auto-configuration runs even if the realms are configured in any way,
        // including defining file based users (defining realms is permitted without touching
        // the xpack.security.enabled setting)
        // but the file realm is required for some of the auto-configuration parts (setting/resetting the elastic user)
        // if disabled, it must be manually enabled back and, preferably, at the head of the realm chain
    }

    // Unfortunately, we cannot tell, for every configuration, if it is going to result in a multi node cluster, as it depends
    // on the addresses that this node, and the others, will bind to when starting (and this runs on a single node before it
    // starts).
    // Here we take a conservative approach: if any of the discovery or initial master nodes setting are set to a non-empty
    // value, we assume the admin intended a multi-node cluster configuration. There is only one exception: if the initial master
    // nodes setting contains just the current node name.
    private boolean isInitialClusterNode(Settings settings) {
        return DiscoveryModule.isSingleNodeDiscovery(settings) ||
                (ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING.get(settings).isEmpty() &&
                        SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING.get(settings).isEmpty() &&
                        DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING.get(settings).isEmpty()) ||
                ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING.get(settings).equals(List.of(Node.NODE_NAME_SETTING.get(settings)));
    }

    private boolean usesTransportTLSWithAutoGeneratedCerts(Environment environment) {
        Settings settings = environment.settings();
        return settings.get("xpack.security.transport.ssl.enabled").equals("true") &&
                settings.get("xpack.security.transport.ssl.verification_mode").equals("certificate") &&
                settings.get("xpack.security.transport.ssl.keystore.path").contains(TRANSPORT_AUTOGENERATED_KEYSTORE_NAME) &&
                Files.isReadable(environment.configFile().resolve(settings.get("xpack.security.transport.ssl.keystore.path"))) &&
                settings.get("xpack.security.transport.ssl.truststore.path")
                        .equals(environment.configFile().resolve(settings.get("xpack.security.transport.ssl.keystore.path")).toString());
    }

    private static void fullyWriteFile(Path basePath, String fileName, boolean replace,
                                       CheckedConsumer<OutputStream, Exception> writer) throws Exception {
        Path filePath = basePath.resolve(fileName);
        if (false == replace && Files.exists(filePath)) {
            throw new UserException(ExitCodes.IO_ERROR, String.format(Locale.ROOT, "Output file [%s] already exists and " +
                    "will not be replaced", filePath));
        }
        // the default permission, if not replacing; if replacing use the permission of the to be replaced file
        Set<PosixFilePermission> permission = PosixFilePermissions.fromString("rw-rw----");
        // if replacing, use the permission of the replaced file
        if (Files.exists(filePath)) {
            PosixFileAttributeView view = Files.getFileAttributeView(filePath, PosixFileAttributeView.class);
            if (view != null) {
                permission = view.readAttributes().permissions();
            }
        }
        Path tmpPath = basePath.resolve(fileName + "." + UUIDs.randomBase64UUID() + ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE_NEW)) {
            writer.accept(outputStream);
            PosixFileAttributeView view = Files.getFileAttributeView(tmpPath, PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(permission);
            }
            if (replace) {
                Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(tmpPath);
        }
    }
}
