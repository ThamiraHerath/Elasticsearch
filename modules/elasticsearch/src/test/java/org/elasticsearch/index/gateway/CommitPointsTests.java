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

package org.elasticsearch.index.gateway;

import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.testng.annotations.Test;

import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
public class CommitPointsTests {

    private final ESLogger logger = Loggers.getLogger(CommitPointsTests.class);

    @Test public void testCommitPointXContent() throws Exception {
        ArrayList<CommitPoint.FileInfo> indexFiles = Lists.newArrayList();
        indexFiles.add(new CommitPoint.FileInfo("file1", "file1_p", 100));
        indexFiles.add(new CommitPoint.FileInfo("file2", "file2_p", 200));

        ArrayList<CommitPoint.FileInfo> translogFiles = Lists.newArrayList();
        translogFiles.add(new CommitPoint.FileInfo("t_file1", "t_file1_p", 100));
        translogFiles.add(new CommitPoint.FileInfo("t_file2", "t_file2_p", 200));

        CommitPoint commitPoint = new CommitPoint(1, "test", CommitPoint.Type.GENERATED, indexFiles, translogFiles);

        byte[] serialized = CommitPoints.toXContent(commitPoint);
        logger.info("serialized commit_point {}", new String(serialized));

        CommitPoint desCp = CommitPoints.fromXContent(serialized);
        assertThat(desCp.version(), equalTo(commitPoint.version()));
        assertThat(desCp.name(), equalTo(commitPoint.name()));

        assertThat(desCp.indexFiles().size(), equalTo(commitPoint.indexFiles().size()));
        for (int i = 0; i < desCp.indexFiles().size(); i++) {
            assertThat(desCp.indexFiles().get(i).name(), equalTo(commitPoint.indexFiles().get(i).name()));
            assertThat(desCp.indexFiles().get(i).physicalName(), equalTo(commitPoint.indexFiles().get(i).physicalName()));
            assertThat(desCp.indexFiles().get(i).length(), equalTo(commitPoint.indexFiles().get(i).length()));
        }

        assertThat(desCp.translogFiles().size(), equalTo(commitPoint.translogFiles().size()));
        for (int i = 0; i < desCp.indexFiles().size(); i++) {
            assertThat(desCp.translogFiles().get(i).name(), equalTo(commitPoint.translogFiles().get(i).name()));
            assertThat(desCp.translogFiles().get(i).physicalName(), equalTo(commitPoint.translogFiles().get(i).physicalName()));
            assertThat(desCp.translogFiles().get(i).length(), equalTo(commitPoint.translogFiles().get(i).length()));
        }
    }
}
