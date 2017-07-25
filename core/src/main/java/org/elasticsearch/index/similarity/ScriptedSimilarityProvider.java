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

package org.elasticsearch.index.similarity;

import org.apache.lucene.search.similarities.Similarity;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.SimilarityScript;

/** Provider of scripted similarities. */
public class ScriptedSimilarityProvider extends AbstractSimilarityProvider {

    private final ScriptedSimilarity scriptedSimilarity;

    public ScriptedSimilarityProvider(String name, Settings settings, Settings indexSettings, ScriptService scriptService) {
        super(name);
        boolean discountOverlaps = settings.getAsBoolean("discount_overlaps", true);
        Settings scriptSettings = settings.getAsSettings("script");
        Script script = Script.parse(scriptSettings);
        SimilarityScript.Factory scriptFactory = scriptService.compile(script, SimilarityScript.CONTEXT);
        Settings initScriptSettings = settings.getAsSettings("init_script");
        Script initScript = null;
        SimilarityScript.Factory initScriptFactory = null;
        if (initScriptSettings.isEmpty() == false) {
            initScript = Script.parse(initScriptSettings);
            initScriptFactory = scriptService.compile(initScript, SimilarityScript.CONTEXT);
        }
        scriptedSimilarity = new ScriptedSimilarity(
                initScript == null ? null : initScript.toString(), initScriptFactory == null ? null : initScriptFactory::newInstance,
                script.toString(), scriptFactory::newInstance, discountOverlaps);
    }

    @Override
    public Similarity get() {
        return scriptedSimilarity;
    }

}
