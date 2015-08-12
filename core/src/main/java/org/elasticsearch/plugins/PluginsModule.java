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

package org.elasticsearch.plugins;

import com.google.common.collect.Lists;
import org.elasticsearch.common.inject.*;
import org.elasticsearch.common.inject.multibindings.Multibinder;
import org.elasticsearch.common.settings.Settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.common.inject.Modules.createModule;

/**
 *
 */
public class PluginsModule extends AbstractModule implements SpawnModules, PreProcessModule {

    private final Settings settings;

    private final PluginsService pluginsService;

    private List<Class<? extends PluginStatsService>> statsServices = new ArrayList<>();

    public PluginsModule(Settings settings, PluginsService pluginsService) {
        this.settings = settings;
        this.pluginsService = pluginsService;
    }

    @Override
    public Iterable<? extends Module> spawnModules() {
        List<Module> modules = Lists.newArrayList();
        Collection<Class<? extends Module>> modulesClasses = pluginsService.modules();
        for (Class<? extends Module> moduleClass : modulesClasses) {
            modules.add(createModule(moduleClass, settings));
        }
        modules.addAll(pluginsService.modules(settings));
        return modules;
    }

    @Override
    public void processModule(Module module) {
        pluginsService.processModule(module);
    }

    @Override
    protected void configure() {
        bind(PluginsService.class).toInstance(pluginsService);

        // Binds plugins statistic services
        Multibinder<PluginStatsService> binder = Multibinder.newSetBinder(binder(), PluginStatsService.class);
        for (Class<? extends PluginStatsService> clazz : statsServices) {
            binder.addBinding().to(clazz);
        }

        // Creates a Guice provider for the set of plugin stats services
        Provider<Set<PluginStatsService>> provider = binder().getProvider(Key.get(new TypeLiteral<Set<PluginStatsService>>() {}));
        // So that the PluginsService will be able to call them in stats() method
        pluginsService.setStatsServices(provider);
    }

    /**
     * Registers a plugin's stats service
     *
     * @param pluginStatsService the plugin's stats service
     */
    public void registerStatsService(Class<? extends PluginStatsService> pluginStatsService) {
        statsServices.add(pluginStatsService);
    }
}
