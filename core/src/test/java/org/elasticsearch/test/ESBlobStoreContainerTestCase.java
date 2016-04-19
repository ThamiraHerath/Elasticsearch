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
package org.elasticsearch.test;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobMetaData;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.bytes.BytesArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.test.ESBlobStoreTestCase.writeRandomBlob;
import static org.elasticsearch.test.ESBlobStoreTestCase.randomBytes;
import static org.elasticsearch.test.ESBlobStoreTestCase.readBlobFully;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * Generic test case for blob store container implementation.
 * These tests check basic blob store functionality.
 */
public abstract class ESBlobStoreContainerTestCase extends ESTestCase {

    public void testWriteRead() throws IOException {
        try(final BlobStore store = newBlobStore()) {
            final BlobContainer container = store.blobContainer(new BlobPath());
            byte[] data = randomBytes(randomIntBetween(10, scaledRandomIntBetween(1024, 1 << 16)));
            container.writeBlob("foobar", new BytesArray(data));
            try (InputStream stream = container.readBlob("foobar")) {
                BytesRefBuilder target = new BytesRefBuilder();
                while (target.length() < data.length) {
                    byte[] buffer = new byte[scaledRandomIntBetween(1, data.length - target.length())];
                    int offset = scaledRandomIntBetween(0, buffer.length - 1);
                    int read = stream.read(buffer, offset, buffer.length - offset);
                    target.append(new BytesRef(buffer, offset, read));
                }
                assertEquals(data.length, target.length());
                assertArrayEquals(data, Arrays.copyOfRange(target.bytes(), 0, target.length()));
            }
        }
    }

    public void testMoveAndList() throws IOException {
        try(final BlobStore store = newBlobStore()) {
            final BlobContainer container = store.blobContainer(new BlobPath());
            assertThat(container.listBlobs().size(), equalTo(0));
            int numberOfFooBlobs = randomIntBetween(0, 10);
            int numberOfBarBlobs = randomIntBetween(3, 20);
            Map<String, Long> generatedBlobs = new HashMap<>();
            for (int i = 0; i < numberOfFooBlobs; i++) {
                int length = randomIntBetween(10, 100);
                String name = "foo-" + i + "-";
                generatedBlobs.put(name, (long) length);
                writeRandomBlob(container, name, length);
            }
            for (int i = 1; i < numberOfBarBlobs; i++) {
                int length = randomIntBetween(10, 100);
                String name = "bar-" + i + "-";
                generatedBlobs.put(name, (long) length);
                writeRandomBlob(container, name, length);
            }
            int length = randomIntBetween(10, 100);
            String name = "bar-0-";
            generatedBlobs.put(name, (long) length);
            byte[] data = writeRandomBlob(container, name, length);

            Map<String, BlobMetaData> blobs = container.listBlobs();
            assertThat(blobs.size(), equalTo(numberOfFooBlobs + numberOfBarBlobs));
            for (Map.Entry<String, Long> generated : generatedBlobs.entrySet()) {
                BlobMetaData blobMetaData = blobs.get(generated.getKey());
                assertThat(generated.getKey(), blobMetaData, notNullValue());
                assertThat(blobMetaData.name(), equalTo(generated.getKey()));
                assertThat(blobMetaData.length(), equalTo(generated.getValue()));
            }

            assertThat(container.listBlobsByPrefix("foo-").size(), equalTo(numberOfFooBlobs));
            assertThat(container.listBlobsByPrefix("bar-").size(), equalTo(numberOfBarBlobs));
            assertThat(container.listBlobsByPrefix("baz-").size(), equalTo(0));

            String newName = "bar-new";
            // Move to a new location
            container.move(name, newName);
            assertThat(container.listBlobsByPrefix(name).size(), equalTo(0));
            blobs = container.listBlobsByPrefix(newName);
            assertThat(blobs.size(), equalTo(1));
            assertThat(blobs.get(newName).length(), equalTo(generatedBlobs.get(name)));
            assertThat(data, equalTo(readBlobFully(container, newName, length)));
        }
    }

    public void testOverwriteFails() throws IOException {
        try (final BlobStore store = newBlobStore()) {
            final String blobName = "foobar";
            final BlobContainer container = store.blobContainer(new BlobPath());
            byte[] data = randomBytes(randomIntBetween(10, scaledRandomIntBetween(1024, 1 << 16)));
            final BytesArray bytesArray = new BytesArray(data);
            container.writeBlob(blobName, bytesArray);
            // should not be able to write to the same blob again
            try {
                container.writeBlob(blobName, bytesArray);
                fail("Cannot overwrite existing blob");
            } catch (AssertionError e) {
                // we want to come here
            }
            container.deleteBlob(blobName);
            container.writeBlob(blobName, bytesArray); // deleted it, so should be able to write it again
        }
    }

    protected abstract BlobStore newBlobStore() throws IOException;
}
