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

package org.elasticsearch.client.security.support.expressiondsl;

import org.elasticsearch.client.security.support.expressiondsl.expressions.AllExpression;
import org.elasticsearch.client.security.support.expressiondsl.expressions.AnyExpression;
import org.elasticsearch.client.security.support.expressiondsl.expressions.ExceptExpression;
import org.elasticsearch.client.security.support.expressiondsl.expressions.CompositeExpressionBuilder;
import org.elasticsearch.client.security.support.expressiondsl.fields.DnFieldExpression;
import org.elasticsearch.client.security.support.expressiondsl.fields.FieldExpressionBuilder;
import org.elasticsearch.client.security.support.expressiondsl.fields.GroupsFieldExpression;
import org.elasticsearch.client.security.support.expressiondsl.fields.MetadataFieldExpression;
import org.elasticsearch.client.security.support.expressiondsl.fields.UsernameFieldExpression;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Date;

import static org.hamcrest.Matchers.equalTo;

public class RoleMapperExpressionDslTests extends ESTestCase {

    public void testRoleMapperExpressionToXContentType() throws IOException {

        final AllExpression allExpression = CompositeExpressionBuilder.builder(AllExpression.class)
                .addExpression(CompositeExpressionBuilder.builder(AnyExpression.class)
                        .addExpression(FieldExpressionBuilder.builder(DnFieldExpression.class)
                                .addValue("*,ou=admin,dc=example,dc=com")
                                .build())
                        .addExpression(FieldExpressionBuilder.builder(UsernameFieldExpression.class)
                                .addValue("es-admin").addValue("es-system")
                                .build())
                        .build())
                .addExpression(FieldExpressionBuilder.builder(GroupsFieldExpression.class)
                        .addValue("cn=people,dc=example,dc=com")
                        .build())
                .addExpression(new ExceptExpression(FieldExpressionBuilder.builder(MetadataFieldExpression.class)
                                            .withKey("metadata.terminated_date")
                                            .addValue(new Date(1537145401027L))
                                            .build()))
                .build();

        final XContentBuilder builder = XContentFactory.jsonBuilder();
        allExpression.toXContent(builder, ToXContent.EMPTY_PARAMS);
        final String output = Strings.toString(builder);
        final String expected =
             "{"+
               "\"all\":["+
                 "{"+
                   "\"any\":["+
                     "{"+
                       "\"field\":{"+
                         "\"dn\":\"*,ou=admin,dc=example,dc=com\""+
                       "}"+
                     "},"+
                     "{"+
                       "\"field\":{"+
                         "\"username\":["+
                           "\"es-admin\","+
                           "\"es-system\""+
                         "]"+
                       "}"+
                     "}"+
                   "]"+
                 "},"+
                 "{"+
                   "\"field\":{"+
                     "\"groups\":\"cn=people,dc=example,dc=com\""+
                   "}"+
                 "},"+
                 "{"+
                   "\"except\":{"+
                     "\"field\":{"+
                       "\"metadata.terminated_date\":\"2018-09-17T00:50:01.027Z\""+
                     "}"+
                   "}"+
                 "}"+
               "]"+
             "}";

        assertThat(expected, equalTo(output));
    }
}
