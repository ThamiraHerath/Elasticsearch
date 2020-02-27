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

package org.elasticsearch.kibana;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.test.rest.ESRestTestCase;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class KibanaSystemIndexIT extends ESRestTestCase {

    public void testBulkToKibanaIndex() throws IOException {
        Request request = new Request("POST", "/_kibana/_bulk");
        request.setJsonEntity("{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"1\" } }\n{ \"foo\" : \"bar\" }\n");
        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), is(200));
    }

    public void testGetFromKibanaIndex() throws IOException {
        Request request = new Request("POST", "/_kibana/_bulk");
        request.setJsonEntity("{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"1\" } }\n{ \"foo\" : \"bar\" }\n");
        request.addParameter("refresh", "true");

        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), is(200));

        Request getRequest = new Request("GET", "/_kibana/.kibana/_doc/1");
        Response getResponse = client().performRequest(getRequest);
        assertThat(getResponse.getStatusLine().getStatusCode(), is(200));
        String responseBody = EntityUtils.toString(getResponse.getEntity());
        assertThat(responseBody, containsString("foo"));
        assertThat(responseBody, containsString("bar"));
    }

    public void testMultiGetFromKibanaIndex() throws IOException {
        Request request = new Request("POST", "/_kibana/_bulk");
        request.setJsonEntity("{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"1\" } }\n{ \"foo\" : \"bar\" }\n" +
            "{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"2\" } }\n{ \"baz\" : \"tag\" }\n");
        request.addParameter("refresh", "true");

        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), is(200));

        Request getRequest = new Request("GET", "/_kibana/_mget");
        getRequest.setJsonEntity("{ \"docs\" : [ { \"_index\" : \".kibana\", \"_id\" : \"1\" }, " +
            "{ \"_index\" : \".kibana\", \"_id\" : \"2\" } ] }\n");
        Response getResponse = client().performRequest(getRequest);
        assertThat(getResponse.getStatusLine().getStatusCode(), is(200));
        String responseBody = EntityUtils.toString(getResponse.getEntity());
        assertThat(responseBody, containsString("foo"));
        assertThat(responseBody, containsString("bar"));
        assertThat(responseBody, containsString("baz"));
        assertThat(responseBody, containsString("tag"));
    }

    public void testSearchFromKibanaIndex() throws IOException {
        Request request = new Request("POST", "/_kibana/_bulk");
        request.setJsonEntity("{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"1\" } }\n{ \"foo\" : \"bar\" }\n" +
            "{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"2\" } }\n{ \"baz\" : \"tag\" }\n");
        request.addParameter("refresh", "true");

        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), is(200));

        Request searchRequest = new Request("GET", "/_kibana/.kibana/_search");
        searchRequest.setJsonEntity("{ \"query\" : { \"match_all\" : {} } }\n");
        Response getResponse = client().performRequest(searchRequest);
        assertThat(getResponse.getStatusLine().getStatusCode(), is(200));
        String responseBody = EntityUtils.toString(getResponse.getEntity());
        assertThat(responseBody, containsString("foo"));
        assertThat(responseBody, containsString("bar"));
        assertThat(responseBody, containsString("baz"));
        assertThat(responseBody, containsString("tag"));
    }

    public void testDeleteFromKibanaIndex() throws IOException {
        Request request = new Request("POST", "/_kibana/_bulk");
        request.setJsonEntity("{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"1\" } }\n{ \"foo\" : \"bar\" }\n" +
            "{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"2\" } }\n{ \"baz\" : \"tag\" }\n");
        request.addParameter("refresh", "true");

        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), is(200));

        Request deleteRequest = new Request("DELETE", "/_kibana/.kibana/_doc/1");
        Response deleteResponse = client().performRequest(deleteRequest);
        assertThat(deleteResponse.getStatusLine().getStatusCode(), is(200));
    }

    public void testDeleteByQueryFromKibanaIndex() throws IOException {
        Request request = new Request("POST", "/_kibana/_bulk");
        request.setJsonEntity("{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"1\" } }\n{ \"foo\" : \"bar\" }\n" +
            "{ \"index\" : { \"_index\" : \".kibana\", \"_id\" : \"2\" } }\n{ \"baz\" : \"tag\" }\n");
        request.addParameter("refresh", "true");

        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), is(200));

        Request dbqRequest = new Request("POST", "/_kibana/.kibana/_delete_by_query");
        dbqRequest.setJsonEntity("{ \"query\" : { \"match_all\" : {} } }\n");
        Response dbqResponse = client().performRequest(dbqRequest);
        assertThat(dbqResponse.getStatusLine().getStatusCode(), is(200));
    }
}
