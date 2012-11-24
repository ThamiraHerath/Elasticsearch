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

package org.elasticsearch.rest;

import java.io.IOException;
import java.util.zip.Adler32;

/**
 *
 */
public abstract class AbstractRestResponse implements RestResponse {

    private long checksum = -1L;    
    
    @Override
    public byte[] prefixContent() {
        return null;
    }

    @Override
    public int prefixContentLength() {
        return -1;
    }

    @Override
    public int prefixContentOffset() {
        return 0;
    }

    @Override
    public byte[] suffixContent() {
        return null;
    }

    @Override
    public int suffixContentLength() {
        return -1;
    }

    @Override
    public int suffixContentOffset() {
        return 0;
    }

    @Override
    public long contentChecksum() {
        if (checksum == -1L) {
            createChecksum();
        }
        return checksum;
    }

    private long createChecksum() {
        Adler32 adler = new Adler32();
        try {
            byte[] b = content();
            adler.update(b,0,b.length);
        } catch (IOException e) {
        }
        return adler.getValue();
    }
    
    
}