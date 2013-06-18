/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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
package org.elasticsearch.search.suggest;

import org.elasticsearch.search.SearchShardTarget;

import java.io.IOException;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.search.SearchParseElement;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.suggest.SuggestionSearchContext.SuggestionContext;

/**
 *
 */
public final class SuggestParseElement implements SearchParseElement {
    private Suggesters suggesters;

    @Inject
    public SuggestParseElement(Suggesters suggesters) {
        this.suggesters = suggesters;
    }

    @Override
    public void parse(XContentParser parser, SearchContext context) throws Exception {
        SuggestionSearchContext suggestionSearchContext = parseInternal(parser, context.mapperService(), context.shardTarget().index(), context.shardTarget().shardId());
        context.suggest(suggestionSearchContext);
    }

    public SuggestionSearchContext parseInternal(XContentParser parser, MapperService mapperService, String index, int shardId) throws IOException {
        SuggestionSearchContext suggestionSearchContext = new SuggestionSearchContext();
        BytesRef globalText = null;
        String fieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                fieldName = parser.currentName();
            } else if (token.isValue()) {
                if ("text".equals(fieldName)) {
                    globalText = parser.bytes();
                } else {
                    throw new ElasticSearchIllegalArgumentException("[suggest] does not support [" + fieldName + "]");
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                String suggestionName = fieldName;
                BytesRef suggestText = null;

                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        fieldName = parser.currentName();
                    } else if (token.isValue()) {
                        if ("text".equals(fieldName)) {
                            suggestText = parser.bytes();
                        } else {
                            throw new ElasticSearchIllegalArgumentException("[suggest] does not support [" + fieldName + "]");
                        }
                    } else if (token == XContentParser.Token.START_OBJECT) {
                        if (suggestionName == null) {
                            throw new ElasticSearchIllegalArgumentException("Suggestion must have name");
                        }
                        if (suggesters.get(fieldName) == null) {
                            throw new ElasticSearchIllegalArgumentException("Suggester[" + fieldName + "] not supported");
                        }
                        final SuggestContextParser contextParser = suggesters.get(fieldName).getContextParser();
                        SuggestionContext suggestion = contextParser.parse(parser, mapperService);
                        suggestion.setText(suggestText);
                        suggestion.setShard(shardId);
                        suggestion.setIndex(index);
                        SuggestUtils.verifySuggestion(mapperService, globalText, suggestion);
                        suggestionSearchContext.addSuggestion(suggestionName, suggestion);
                    }
                }
            }
        }
        return suggestionSearchContext;
    }


   
}
