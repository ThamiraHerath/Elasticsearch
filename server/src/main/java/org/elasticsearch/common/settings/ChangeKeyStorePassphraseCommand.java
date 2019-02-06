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

package org.elasticsearch.common.settings;

import joptsimple.OptionSet;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.env.Environment;

import java.util.Arrays;

public class ChangeKeyStorePassphraseCommand extends EnvironmentAwareCommand {


    ChangeKeyStorePassphraseCommand() {
        super("Changes the passphrase of a keystore");
    }

    @Override
    protected void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
        char[] newPassphrase = null;
        try (KeystoreAndPassphrase keyAndPass = KeyStoreWrapper.readOrCreate(terminal, env.configFile(), false)) {
            if (null == keyAndPass) {
                return;
            }
            KeyStoreWrapper keystore = keyAndPass.getKeystore();
            newPassphrase = KeyStoreWrapper.readPassphrase(terminal, true);
            keystore.save(env.configFile(), newPassphrase);
            terminal.println("Elasticsearch keystore passphrase changed successfully." + env.configFile());
        } catch (SecurityException e) {
            throw new UserException(ExitCodes.DATA_ERROR, "Failed to access the keystore. Please make sure the passphrase was correct.");
        } finally {
            if (null != newPassphrase) {
                Arrays.fill(newPassphrase, '\u0000');
            }
        }
    }
}
