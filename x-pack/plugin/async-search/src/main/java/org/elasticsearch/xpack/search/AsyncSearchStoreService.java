/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.search;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.OriginSettingClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.search.action.AsyncSearchResponse;
import org.elasticsearch.xpack.core.security.authc.Authentication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.action.admin.cluster.node.tasks.get.GetTaskAction.TASKS_ORIGIN;
import static org.elasticsearch.xpack.core.security.authc.AuthenticationField.AUTHENTICATION_KEY;
import static org.elasticsearch.xpack.search.AsyncSearchTemplateRegistry.INDEX_TEMPLATE_VERSION;
import static org.elasticsearch.xpack.search.AsyncSearchTemplateRegistry.ASYNC_SEARCH_TEMPLATE_NAME;

/**
 * A class that encapsulates the logic to store and retrieve {@link AsyncSearchResponse} to/from the .async-search index.
 */
class AsyncSearchStoreService {
    private static final Logger logger = LogManager.getLogger(AsyncSearchStoreService.class);

    static final String ASYNC_SEARCH_ALIAS = ASYNC_SEARCH_TEMPLATE_NAME + "-" + INDEX_TEMPLATE_VERSION;
    static final String ASYNC_SEARCH_INDEX_PREFIX = ASYNC_SEARCH_ALIAS + "-";
    static final String RESULT_FIELD = "result";
    static final String HEADERS_FIELD = "headers";

    private final TaskManager taskManager;
    private final ThreadPool threadPool;
    private final Client client;
    private final NamedWriteableRegistry registry;

    AsyncSearchStoreService(TaskManager taskManager, ThreadPool threadPool, Client client, NamedWriteableRegistry registry) {
        this.taskManager = taskManager;
        this.threadPool = threadPool;
        this.client = new OriginSettingClient(client, TASKS_ORIGIN);
        this.registry = registry;
    }

    /**
     * Return the internal client with origin.
     */
    Client getClient() {
        return client;
    }

    /**
     * Checks if the async-search index exists, and if not, creates it.
     * The provided {@link ActionListener} is called with the index name that should
     * be used to store the response.
     */
    void ensureAsyncSearchIndex(ClusterState state, ActionListener<String> andThen) {
        final String initialIndexName = ASYNC_SEARCH_INDEX_PREFIX + "000001";
        final AliasOrIndex current = state.metaData().getAliasAndIndexLookup().get(ASYNC_SEARCH_ALIAS);
        final AliasOrIndex initialIndex = state.metaData().getAliasAndIndexLookup().get(initialIndexName);
        if (current == null && initialIndex == null) {
            // No alias or index exists with the expected names, so create the index with appropriate alias
            client.admin().indices().prepareCreate(initialIndexName)
                .setWaitForActiveShards(1)
                .addAlias(new Alias(ASYNC_SEARCH_ALIAS).writeIndex(true))
                .execute(new ActionListener<>() {
                    @Override
                    public void onResponse(CreateIndexResponse response) {
                        andThen.onResponse(initialIndexName);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (e instanceof ResourceAlreadyExistsException) {
                            // The index didn't exist before we made the call, there was probably a race - just ignore this
                            andThen.onResponse(initialIndexName);
                        } else {
                            andThen.onFailure(e);
                        }
                    }
                });
        } else if (current == null) {
            // alias does not exist but initial index does, something is broken
            andThen.onFailure(new IllegalStateException("async-search index [" + initialIndexName +
                "] already exists but does not have alias [" + ASYNC_SEARCH_ALIAS + "]"));
        } else if (current.isAlias() && current instanceof AliasOrIndex.Alias) {
            AliasOrIndex.Alias alias = (AliasOrIndex.Alias) current;
            if (alias.getWriteIndex() != null) {
                // The alias exists and has a write index, so we're good
                andThen.onResponse(alias.getWriteIndex().getIndex().getName());
            } else {
                // The alias does not have a write index, so we can't index into it
                andThen.onFailure(new IllegalStateException("async-search alias [" + ASYNC_SEARCH_ALIAS + "] does not have a write index"));
            }
        } else if (current.isAlias() == false) {
            // This is not an alias, error out
            andThen.onFailure(new IllegalStateException("async-search alias [" + ASYNC_SEARCH_ALIAS +
                "] already exists as concrete index"));
        } else {
            logger.error("unexpected IndexOrAlias for [{}]: [{}]", ASYNC_SEARCH_ALIAS, current);
            andThen.onFailure(new IllegalStateException("unexpected IndexOrAlias for async-search index"));
            assert false : ASYNC_SEARCH_ALIAS + " cannot be both an alias and not an alias simultaneously";
        }
    }

    /**
     * Store an empty document in the .async-search index that is used
     * as a place-holder for the future response.
     */
    void storeInitialResponse(Map<String, String> headers, String index, String docID, AsyncSearchResponse response,
                              ActionListener<IndexResponse> listener) throws IOException {
        Map<String, Object> source = new HashMap<>();
        source.put(RESULT_FIELD, encodeResponse(response));
        source.put(HEADERS_FIELD, headers);
        IndexRequest request = new IndexRequest(index)
            .id(docID)
            .source(source, XContentType.JSON);
        client.index(request, listener);
    }

    /**
     * Store the final response if the place-holder document is still present (update).
     */
    void storeFinalResponse(Map<String, String> headers, AsyncSearchResponse response,
                            ActionListener<UpdateResponse> listener) throws IOException {
        AsyncSearchId searchId = AsyncSearchId.decode(response.getId());
        Map<String, Object> source = new HashMap<>();
        source.put(RESULT_FIELD, encodeResponse(response));
        source.put(HEADERS_FIELD, headers);
        UpdateRequest request = new UpdateRequest().index(searchId.getIndexName()).id(searchId.getDocId())
            .doc(source, XContentType.JSON)
            .detectNoop(false);
        client.update(request, listener);
    }

    AsyncSearchTask getTask(AsyncSearchId searchId) throws IOException {
        Task task = taskManager.getTask(searchId.getTaskId().getId());
        if (task == null || task instanceof AsyncSearchTask == false) {
            return null;
        }
        AsyncSearchTask searchTask = (AsyncSearchTask) task;
        if (searchTask.getSearchId().equals(searchId) == false) {
            return null;
        }

        // Check authentication for the user
        final Authentication auth = Authentication.getAuthentication(threadPool.getThreadContext());
        if (ensureAuthenticatedUserIsSame(searchTask.getOriginHeaders(), auth) == false) {
            throw new ResourceNotFoundException(searchId.getEncoded() + " not found");
        }
        return searchTask;
    }

    /**
     * Get the response from the .async-search index if present, or delegate a {@link ResourceNotFoundException}
     * failure to the provided listener if not.
     */
    void getResponse(AsyncSearchId searchId, ActionListener<AsyncSearchResponse> listener) {
        final Authentication current = Authentication.getAuthentication(client.threadPool().getThreadContext());
        GetRequest internalGet = new GetRequest(searchId.getIndexName())
            .preference(searchId.getEncoded())
            .id(searchId.getDocId());
        client.get(internalGet, ActionListener.wrap(
            get -> {
                if (get.isExists() == false) {
                    listener.onFailure(new ResourceNotFoundException(searchId.getEncoded() + " not found"));
                    return;
                }

                // check the authentication of the current user against the user that initiated the async search
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) get.getSource().get(HEADERS_FIELD);
                if (ensureAuthenticatedUserIsSame(headers, current) == false) {
                    listener.onFailure(new ResourceNotFoundException(searchId.getEncoded() + " not found"));
                    return;
                }

                @SuppressWarnings("unchecked")
                String encoded = (String) get.getSource().get(RESULT_FIELD);
                listener.onResponse(encoded != null ? decodeResponse(encoded, registry) : null);
            },
            listener::onFailure
        ));
    }

    void deleteResult(AsyncSearchId searchId, ActionListener<AcknowledgedResponse> listener) {
        DeleteRequest request = new DeleteRequest(searchId.getIndexName()).id(searchId.getDocId());
        client.delete(request, ActionListener.wrap(
            resp -> {
                if (resp.status() == RestStatus.NOT_FOUND) {
                    listener.onFailure(new ResourceNotFoundException("id [{}] not found", searchId.getEncoded()));
                } else {
                    listener.onResponse(new AcknowledgedResponse(true));
                }
            },
            exc -> {
                logger.error(() -> new ParameterizedMessage("failed to clean async-search [{}]", searchId.getEncoded()), exc);
                listener.onFailure(exc);
            })
        );
    }

    /**
     * Extracts the authentication from the original headers and checks that it matches
     * the current user. This function returns always <code>true</code> if the provided
     * <code>headers</code> do not contain any authentication.
     */
    static boolean ensureAuthenticatedUserIsSame(Map<String, String> originHeaders, Authentication current) throws IOException {
        if (originHeaders == null || originHeaders.containsKey(AUTHENTICATION_KEY) == false) {
            // no authorization attached to the original request
            return true;
        }
        if (current == null) {
            // origin is an authenticated user but current is not
            return false;
        }
        Authentication origin = Authentication.decode(originHeaders.get(AUTHENTICATION_KEY));
        return ensureAuthenticatedUserIsSame(origin, current);
    }

    /**
     * Compares the {@link Authentication} that was used to create the {@link AsyncSearchId} with the
     * current authentication.
     */
    static boolean ensureAuthenticatedUserIsSame(Authentication original, Authentication current) {
        final boolean samePrincipal = original.getUser().principal().equals(current.getUser().principal());
        final boolean sameRealmType;
        if (original.getUser().isRunAs()) {
            if (current.getUser().isRunAs()) {
                sameRealmType = original.getLookedUpBy().getType().equals(current.getLookedUpBy().getType());
            }  else {
                sameRealmType = original.getLookedUpBy().getType().equals(current.getAuthenticatedBy().getType());
            }
        } else if (current.getUser().isRunAs()) {
            sameRealmType = original.getAuthenticatedBy().getType().equals(current.getLookedUpBy().getType());
        } else {
            sameRealmType = original.getAuthenticatedBy().getType().equals(current.getAuthenticatedBy().getType());
        }
        return samePrincipal && sameRealmType;
    }

    /**
     * Encode the provided response in a binary form using base64 encoding.
     */
    static String encodeResponse(AsyncSearchResponse response) throws IOException {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            Version.writeVersion(Version.CURRENT, out);
            response.writeTo(out);
            return Base64.getEncoder().encodeToString(BytesReference.toBytes(out.bytes()));
        }
    }

    /**
     * Decode the provided base-64 bytes into a {@link AsyncSearchResponse}.
     */
    static AsyncSearchResponse decodeResponse(String value, NamedWriteableRegistry registry) throws IOException {
        try (ByteBufferStreamInput buf = new ByteBufferStreamInput(ByteBuffer.wrap(Base64.getDecoder().decode(value)))) {
            try (StreamInput in = new NamedWriteableAwareStreamInput(buf, registry)) {
                in.setVersion(Version.readVersion(in));
                return new AsyncSearchResponse(in);
            }
        }
    }
}
