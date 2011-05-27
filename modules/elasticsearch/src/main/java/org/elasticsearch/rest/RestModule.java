/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.rest;

import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.rest.action.RestActionModule;

import java.util.List;

/**
 * @author kimchy (Shay Banon)
 */
public class RestModule extends AbstractModule  {

    private final Settings settings;
    private List<Class<? extends RestHandler>> restPluginsActions = Lists.newArrayList();
    private List<Class<? extends RestHandler>> disabledDefaultRestActions = Lists.newArrayList();
    private final PluginsService pluginsService;

    public void addRestAction(Class<? extends BaseRestHandler> restAction) {
        restPluginsActions.add(restAction);
    }

    public void removeDefaultRestActions(Class<? extends RestHandler> restAction) {
        disabledDefaultRestActions.add(restAction);
    }

    public RestModule(Settings settings,  PluginsService pluginsService) {
        this.settings = settings;
        this.pluginsService = pluginsService;
    }

    @Override protected void configure() {
        bind(RestController.class).asEagerSingleton();
        new RestActionModule(restPluginsActions, disabledDefaultRestActions, settings).configure(binder());
    }

}
