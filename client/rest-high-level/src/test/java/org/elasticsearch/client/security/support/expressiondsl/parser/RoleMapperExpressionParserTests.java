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

package org.elasticsearch.client.security.support.expressiondsl.parser;

import org.elasticsearch.client.security.support.expressiondsl.RoleMapperExpression;
import org.elasticsearch.client.security.support.expressiondsl.expressions.AllExpression;
import org.elasticsearch.client.security.support.expressiondsl.expressions.AnyExpression;
import org.elasticsearch.client.security.support.expressiondsl.expressions.ExceptExpression;
import org.elasticsearch.client.security.support.expressiondsl.fields.GroupsFieldExpression;
import org.elasticsearch.client.security.support.expressiondsl.fields.UsernameFieldExpression;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Collections;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.iterableWithSize;

public class RoleMapperExpressionParserTests extends ESTestCase {

    public void testParseSimpleFieldExpression() throws Exception {
        String json = "{ \"field\": { \"username\" : \"*@shield.gov\" } }";
        UsernameFieldExpression field = checkExpressionType(parse(json), UsernameFieldExpression.class);
        assertThat(field.getField(), equalTo("username"));
        assertThat(field.getValues(), iterableWithSize(1));
        assertThat(field.getValues().get(0), equalTo("*@shield.gov"));

        assertThat(toJson(field), equalTo(json.replaceAll("\\s", "")));
    }

    public void testParseComplexExpression() throws Exception {
        String json = "{ \"any\": [" +
                "   { \"field\": { \"username\" : \"*@shield.gov\" } }, " +
                "   { \"all\": [" +
                "     { \"field\": { \"username\" : \"/.*\\\\@avengers\\\\.(net|org)/\" } }, " +
                "     { \"field\": { \"groups\" : [ \"admin\", \"operators\" ] } }, " +
                "     { \"except\":" +
                "       { \"field\": { \"groups\" : \"disavowed\" } }" +
                "     }" +
                "   ] }" +
                "] }";
        final RoleMapperExpression expr = parse(json);

        assertThat(expr, instanceOf(AnyExpression.class));
        AnyExpression any = (AnyExpression) expr;

        assertThat(any.getElements(), iterableWithSize(2));

        final UsernameFieldExpression fieldShield = checkExpressionType(any.getElements().get(0),
                UsernameFieldExpression.class);
        assertThat(fieldShield.getField(), equalTo("username"));
        assertThat(fieldShield.getValues(), iterableWithSize(1));
        assertThat(fieldShield.getValues().get(0), equalTo("*@shield.gov"));

        final AllExpression all = checkExpressionType(any.getElements().get(1),
                AllExpression.class);
        assertThat(all.getElements(), iterableWithSize(3));

        final UsernameFieldExpression fieldAvengers = checkExpressionType(all.getElements().get(0),
                UsernameFieldExpression.class);
        assertThat(fieldAvengers.getField(), equalTo("username"));
        assertThat(fieldAvengers.getValues(), iterableWithSize(1));
        assertThat(fieldAvengers.getValues().get(0), equalTo("/.*\\@avengers\\.(net|org)/"));

        final GroupsFieldExpression fieldGroupsAdmin = checkExpressionType(all.getElements().get(1),
                GroupsFieldExpression.class);
        assertThat(fieldGroupsAdmin.getField(), equalTo("groups"));
        assertThat(fieldGroupsAdmin.getValues(), iterableWithSize(2));
        assertThat(fieldGroupsAdmin.getValues().get(0), equalTo("admin"));
        assertThat(fieldGroupsAdmin.getValues().get(1), equalTo("operators"));

        final ExceptExpression except = checkExpressionType(all.getElements().get(2),
                ExceptExpression.class);
        final GroupsFieldExpression fieldDisavowed = checkExpressionType(except.getInnerExpression(),
                GroupsFieldExpression.class);
        assertThat(fieldDisavowed.getField(), equalTo("groups"));
        assertThat(fieldDisavowed.getValues(), iterableWithSize(1));
        assertThat(fieldDisavowed.getValues().get(0), equalTo("disavowed"));

        assertThat(toJson(expr), equalTo(json.replaceAll("\\s", "")));
    }

    private String toJson(final RoleMapperExpression expr) throws IOException {
        final XContentBuilder builder = XContentFactory.jsonBuilder();
        expr.toXContent(builder, ToXContent.EMPTY_PARAMS);
        final String output = Strings.toString(builder);
        return output;
    }

    private <T> T checkExpressionType(RoleMapperExpression expr, Class<T> type) {
        assertThat(expr, instanceOf(type));
        return type.cast(expr);
    }

    private RoleMapperExpression parse(String json) throws IOException {
        return new RoleMapperExpressionParser().parse("rules", XContentType.JSON.xContent().createParser(new NamedXContentRegistry(
                Collections.emptyList()), new DeprecationHandler() {
                    @Override
                    public void usedDeprecatedName(String usedName, String modernName) {
                    }

                    @Override
                    public void usedDeprecatedField(String usedName, String replacedWith) {
                    }
                }, json));
    }

}
