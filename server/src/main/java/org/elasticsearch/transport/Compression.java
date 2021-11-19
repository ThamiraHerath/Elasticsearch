/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.transport;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;

import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.lz4.ESLZ4Compressor;
import org.elasticsearch.lz4.ESLZ4Decompressor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Compression {

    public enum Scheme {
        LZ4,
        DEFLATE;

        static final Version LZ4_VERSION = Version.V_7_14_0;
        static final int HEADER_LENGTH = 4;
        private static final byte[] DEFLATE_HEADER = new byte[] { 'D', 'F', 'L', '\0' };
        private static final byte[] LZ4_HEADER = new byte[] { 'L', 'Z', '4', '\0' };
        private static final int LZ4_BLOCK_SIZE;
        private static final LZ4FrameOutputStream.BLOCKSIZE LZ4_FRAME_BLOCK_SIZE;
        private static final LZ4FrameOutputStream.FLG.Bits BLOCK_INDEPENDENCE = LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE;
        private static final boolean USE_FORKED_LZ4;

        static {
            String blockSizeString = System.getProperty("es.transport.compression.lz4_block_size");
            if (blockSizeString != null) {
                int lz4BlockSize = Integer.parseInt(blockSizeString);
                if (lz4BlockSize < 1024 || lz4BlockSize > (512 * 1024)) {
                    throw new IllegalArgumentException("lz4_block_size must be >= 1KB and <= 512KB");
                }
                LZ4_BLOCK_SIZE = lz4BlockSize;
                if (lz4BlockSize != 64 * 1024) {
                    LZ4_FRAME_BLOCK_SIZE = LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB;
                } else {
                    LZ4_FRAME_BLOCK_SIZE = LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB;
                }
            } else {
                LZ4_BLOCK_SIZE = 64 * 1024;
                LZ4_FRAME_BLOCK_SIZE = LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB;
            }

            USE_FORKED_LZ4 = Booleans.parseBoolean(System.getProperty("es.compression.use_forked_lz4", "true"));
        }

        public static boolean isDeflate(BytesReference bytes) {
            byte firstByte = bytes.get(0);
            if (firstByte != Compression.Scheme.DEFLATE_HEADER[0]) {
                return false;
            } else {
                return validateHeader(bytes, DEFLATE_HEADER);
            }
        }

        public static boolean isLZ4(BytesReference bytes) {
            byte firstByte = bytes.get(0);
            if (firstByte != Scheme.LZ4_HEADER[0]) {
                return false;
            } else {
                return validateHeader(bytes, LZ4_HEADER);
            }
        }

        private static boolean validateHeader(BytesReference bytes, byte[] header) {
            for (int i = 1; i < Compression.Scheme.HEADER_LENGTH; ++i) {
                if (bytes.get(i) != header[i]) {
                    return false;
                }
            }
            return true;
        }

        public static LZ4FastDecompressor lz4Decompressor() {
            if (USE_FORKED_LZ4) {
                return ESLZ4Decompressor.INSTANCE;
            } else {
                return LZ4Factory.safeInstance().fastDecompressor();
            }
        }

        public static OutputStream lz4OutputStream(OutputStream outputStream) throws IOException {
            outputStream.write(LZ4_HEADER);
            LZ4Compressor lz4Compressor;
            if (USE_FORKED_LZ4) {
                lz4Compressor = ESLZ4Compressor.INSTANCE;
            } else {
                lz4Compressor = LZ4Factory.safeInstance().fastCompressor();
            }
            return new ReuseBuffersLZ4BlockOutputStream(outputStream, LZ4_BLOCK_SIZE, lz4Compressor);
        }

        public static InputStream lz4FrameInputStream(InputStream inputStream) throws IOException {
            XXHash32 checksum = XXHashFactory.fastestInstance().hash32();
            // TODO: Change to forked instance
            LZ4SafeDecompressor lz4SafeDecompressor = LZ4Factory.safeInstance().safeDecompressor();
            return new LZ4FrameInputStream(inputStream, lz4SafeDecompressor, checksum);
        }

        public static OutputStream lz4FrameOutputStream(OutputStream outputStream) throws IOException {
            outputStream.write(LZ4_HEADER);
            LZ4Compressor lz4Compressor;
            if (USE_FORKED_LZ4) {
                lz4Compressor = ESLZ4Compressor.INSTANCE;
            } else {
                lz4Compressor = LZ4Factory.safeInstance().fastCompressor();
            }
            XXHash32 checksum = XXHashFactory.fastestInstance().hash32();
            return new LZ4FrameOutputStream(outputStream, LZ4_FRAME_BLOCK_SIZE, -1, lz4Compressor, checksum, BLOCK_INDEPENDENCE);
        }
    }

    public enum Enabled {
        TRUE,
        INDEXING_DATA,
        FALSE
    }
}
