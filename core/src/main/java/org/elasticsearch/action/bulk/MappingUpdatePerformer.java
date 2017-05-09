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

package org.elasticsearch.action.bulk;

import org.elasticsearch.index.mapper.Mapping;
import org.elasticsearch.index.shard.ShardId;

public interface MappingUpdatePerformer {

    /**
     * Update the mappings on the master.
     */
    void updateMappings(Mapping update, ShardId shardId, String type) throws Exception;

    /**
     *  Throws a {@code ReplicationOperation.RetryOnPrimaryException} if the operation needs to be
     * retried on the primary due to the mappings not being present yet, or a different exception if
     * updating the mappings on the master failed.
     */
    void verifyMappings(Mapping update, ShardId shardId) throws Exception;

}
