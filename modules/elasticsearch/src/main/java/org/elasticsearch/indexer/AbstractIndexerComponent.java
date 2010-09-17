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

package org.elasticsearch.indexer;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

/**
 * @author kimchy (shay.banon)
 */
public class AbstractIndexerComponent implements IndexerComponent {

    protected final ESLogger logger;

    protected final IndexerName indexerName;

    protected final IndexerSettings settings;

    protected AbstractIndexerComponent(IndexerName indexerName, IndexerSettings settings) {
        this.indexerName = indexerName;
        this.settings = settings;

        this.logger = Loggers.getLogger(getClass(), settings.globalSettings(), indexerName);
    }

    @Override public IndexerName indexerName() {
        return indexerName;
    }

    public String nodeName() {
        return settings.globalSettings().get("name", "");
    }
}