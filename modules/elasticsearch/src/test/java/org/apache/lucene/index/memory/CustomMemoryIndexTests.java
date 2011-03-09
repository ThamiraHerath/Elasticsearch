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

package org.apache.lucene.index.memory;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.common.lucene.Lucene;
import org.testng.annotations.Test;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

public class CustomMemoryIndexTests {

    @Test public void testSameFieldSeveralTimes() throws Exception {
        CustomMemoryIndex memoryIndex = new CustomMemoryIndex();
        memoryIndex.addField("field1", "value1", Lucene.KEYWORD_ANALYZER);
        memoryIndex.addField("field1", "value2", Lucene.KEYWORD_ANALYZER);

        IndexSearcher searcher = memoryIndex.createSearcher();
        assertThat(searcher.search(new TermQuery(new Term("field1", "value1")), 10).totalHits, equalTo(1));
        assertThat(searcher.search(new TermQuery(new Term("field1", "value2")), 10).totalHits, equalTo(1));
    }
}