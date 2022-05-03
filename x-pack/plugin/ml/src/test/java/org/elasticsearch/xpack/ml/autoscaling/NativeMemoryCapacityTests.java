/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.autoscaling;

import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.autoscaling.capacity.AutoscalingCapacity;
import org.elasticsearch.xpack.ml.utils.NativeMemoryCalculator;

import java.util.function.BiConsumer;

import static org.elasticsearch.xpack.ml.MachineLearning.NATIVE_EXECUTABLE_CODE_OVERHEAD;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

public class NativeMemoryCapacityTests extends ESTestCase {

    private static final int NUM_TEST_RUNS = 10;

    public void testMerge() {
        NativeMemoryCapacity capacity = new NativeMemoryCapacity(
            ByteSizeValue.ofGb(1).getBytes(),
            ByteSizeValue.ofMb(200).getBytes(),
            ByteSizeValue.ofMb(50).getBytes()
        );
        capacity = capacity.merge(new NativeMemoryCapacity(ByteSizeValue.ofGb(1).getBytes(), ByteSizeValue.ofMb(100).getBytes()));
        assertThat(capacity.getTierMlNativeMemoryRequirementExcludingOverhead(), equalTo(ByteSizeValue.ofGb(1).getBytes() * 2L));
        assertThat(capacity.getNodeMlNativeMemoryRequirementExcludingOverhead(), equalTo(ByteSizeValue.ofMb(200).getBytes()));
        // We cannot know the JVM size will stay the same as the bigger tier may lead to bigger nodes
        assertThat(capacity.getJvmSize(), nullValue());

        capacity = capacity.merge(new NativeMemoryCapacity(ByteSizeValue.ofGb(1).getBytes(), ByteSizeValue.ofMb(300).getBytes()));

        assertThat(capacity.getTierMlNativeMemoryRequirementExcludingOverhead(), equalTo(ByteSizeValue.ofGb(1).getBytes() * 3L));
        assertThat(capacity.getNodeMlNativeMemoryRequirementExcludingOverhead(), equalTo(ByteSizeValue.ofMb(300).getBytes()));
        assertThat(capacity.getJvmSize(), nullValue());
    }

    public void testAutoscalingCapacity() {

        final long BYTES_IN_64GB = ByteSizeValue.ofGb(64).getBytes();
        final long AUTO_ML_MEMORY_FOR_64GB_NODE = NativeMemoryCalculator.allowedBytesForMl(BYTES_IN_64GB, randomIntBetween(5, 90), true);

        NativeMemoryCapacity capacity = new NativeMemoryCapacity(
            ByteSizeValue.ofGb(4).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
            ByteSizeValue.ofGb(1).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
            ByteSizeValue.ofMb(50).getBytes()
        );

        // auto is false (which should not be when autoscaling is used as intended)
        {
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                25,
                false,
                NativeMemoryCalculator.allowedBytesForMl(BYTES_IN_64GB, 25, false),
                1
            );
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(ByteSizeValue.ofGb(1).getBytes() * 4L));
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(ByteSizeValue.ofGb(4).getBytes() * 4L));
        }
        // auto is true (so configured max memory percent should be ignored)
        {
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(1335885824L));
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(4557111296L));
        }
        // auto is true with unknown jvm size, memory requirement below JVM size knot point, 1 AZ (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(4).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(1).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(2139095040L));
            // 7507804160 bytes = 7160MB
            // 7160MB node => 2864MB JVM heap (40% of 7160MB)
            // 7160MB - 2864MB - 200MB = 4096MB which is what we asked for for the tier
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(7507804160L));
        }
        // auto is true with unknown jvm size, memory requirement below JVM size knot point, 2 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(4).getBytes() - 2 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(1).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                2
            );
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(2139095040L));
            // 7855931392 bytes = 7492MB
            // We expect to be given 2 nodes as there are 2 AZs, so each will be 3746MB
            // 3746MB node => 1498MB JVM heap (40% of 3746MB rounded down)
            // 3746MB - 1498MB - 200MB = 2048MB which is half of what we asked for for the tier
            // So with 2 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(7855931392L));
        }
        // auto is true with unknown jvm size, memory requirement below JVM size knot point, 3 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(4).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(1).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                3
            );
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(2139095040L));
            // 8205107202 bytes = 7825MB + 2 bytes
            // We expect to be given 3 nodes as there are 3 AZs, so each will be 2608 1/3MB
            // 2608 1/3MB node => 1043MB JVM heap (40% of 2608 1/3MB rounded down)
            // 2608 1/3MB - 1043MB - 200MB = 1365 1/3MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            // (The 2 byte discrepancy comes from the fact there are 3 nodes and 3 didn't divide exactly into the amount
            // of memory we needed, so each node gets a fraction of a byte extra to take it up to a whole number size)
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(8205107202L));
        }
        // auto is true with unknown jvm size, memory requirement below JVM size knot point, 1 AZ (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(4).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(3).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            // 5717884928 bytes = 5453MB
            // 5453MB node => 2181MB JVM heap (40% of 5453MB rounded down)
            // 5453MB - 2181MB - 200MB = 3072MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(5717884928L));
            // 7507804160 bytes = 7160MB
            // 7160MB node => 2864MB JVM heap (40% of 7160MB)
            // 7160MB - 2864MB - 200MB = 4096MB which is what we asked for for the tier
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(7507804160L));
        }
        // auto is true with unknown jvm size, memory requirement below JVM size knot point, 2 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(4).getBytes() - 2 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(3).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                2
            );
            // 5717884928 bytes = 5453MB
            // 5453MB node => 2181MB JVM heap (40% of 5453MB rounded down)
            // 5453MB - 2181MB - 200MB = 3072MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(5717884928L));
            // 7855931392 bytes = 7492MB
            // We expect to be given 2 nodes as there are 2 AZs, so each will be 3746MB
            // 3746MB node => 1498MB JVM heap (40% of 3746MB rounded down)
            // 3746MB - 1498MB - 200MB = 2048MB which is half of what we asked for for the tier
            // So with 2 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(7855931392L));
        }
        // auto is true with unknown jvm size, memory requirement below JVM size knot point, 3 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(4).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(3).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                3
            );
            // 5717884928 bytes = 5453MB
            // 5453MB node => 2181MB JVM heap (40% of 5453MB rounded down)
            // 5453MB - 2181MB - 200MB = 3072MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(5717884928L));
            // 8205107202 bytes = 7825MB + 2 bytes
            // We expect to be given 3 nodes as there are 3 AZs, so each will be 2608 1/3MB
            // 2608 1/3MB node => 1043MB JVM heap (40% of 2608 1/3MB rounded down)
            // 2608 1/3MB - 1043MB - 200MB = 1365 1/3MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            // (The 2 byte discrepancy comes from the fact there are 3 nodes and 3 didn't divide exactly into the amount
            // of memory we needed, so each node gets a fraction of a byte extra to take it up to a whole number size)
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(8205107202L));
        }
        // auto is true with unknown jvm size, memory requirement above JVM size knot point, 1 AZ (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(30).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(5).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            // 9296674816 bytes = 8866MB
            // 8866MB node => 3546MB JVM heap (40% of 8866MB rounded down)
            // 8866MB - 3546MB - 200MB = 5120MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(9296674816L));
            // 41750102016 bytes = 39816MB
            // 39816MB node => 8896MB JVM heap (40% of 16384MB + 10% of 23432MB rounded down)
            // 39816MB - 8896MB - 200MB = 30720MB which is what we asked for for the tier
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(41750102016L));
        }
        // auto is true with unknown jvm size, memory requirement above JVM size knot point, 2 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(30).getBytes() - 2 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(5).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                2
            );
            // 9296674816 bytes = 8866MB
            // 8866MB node => 3546MB JVM heap (40% of 8866MB rounded down)
            // 8866MB - 3546MB - 200MB = 5120MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(9296674816L));
            // 47710208000 bytes = 45500MB
            // We expect to be given 2 nodes as there are 2 AZs, so each will be 22750MB
            // 22750MB node => 7190MB JVM heap (40% of 16384MB + 10% of 6366MB rounded down)
            // 22750MB - 7190MB - 200MB = 15360MB which is half of what we asked for for the tier
            // So with 2 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(47710208000L));
        }
        // auto is true with unknown jvm size, memory requirement above JVM size knot point, 3 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(30).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(5).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                3
            );
            // 9296674816 bytes = 8866MB
            // 8866MB node => 3546MB JVM heap (40% of 8866MB rounded down)
            // 8866MB - 3546MB - 200MB = 5120MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(9296674816L));
            // 53669265408 bytes = 51183MB
            // We expect to be given 3 nodes as there are 3 AZs, so each will be 17061MB
            // 17061MB node => 6621MB JVM heap (40% of 16384MB + 10% of 677MB rounded down)
            // 17061MB - 6621MB - 200MB = 10240MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(53669265408L));
        }
        // auto is true with unknown jvm size, memory requirement above JVM size knot point, 1 AZ (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(30).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(20).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            // 29820452864 bytes = 28439MB
            // 28439MB node => 7759MB JVM heap (40% of 16384MB + 10% of 12055MB rounded down)
            // 28439MB - 7759MB - 200MB = 20480MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(29820452864L));
            // 41750102016 bytes = 39816MB
            // 39816MB node => 8896MB JVM heap (40% of 16384MB + 10% of 23432MB rounded down)
            // 39816MB - 8896MB - 200MB = 30720MB which is what we asked for for the tier
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(41750102016L));
        }
        // auto is true with unknown jvm size, memory requirement above JVM size knot point, 2 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(30).getBytes() - 2 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(20).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                2
            );
            // 29820452864 bytes = 28439MB
            // 28439MB node => 7759MB JVM heap (40% of 16384MB + 10% of 12055MB rounded down)
            // 28439MB - 7759MB - 200MB = 20480MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(29820452864L));
            // 47710208000 bytes = 45500MB
            // We expect to be given 2 nodes as there are 2 AZs, so each will be 22750MB
            // 22750MB node => 7190MB JVM heap (40% of 16384MB + 10% of 6366MB rounded down)
            // 22750MB - 7190MB - 200MB = 15360MB which is half of what we asked for for the tier
            // So with 2 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(47710208000L));
        }
        // auto is true with unknown jvm size, memory requirement above JVM size knot point, 3 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(30).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(20).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                3
            );
            // 29820452864 bytes = 28439MB
            // 28439MB node => 7759MB JVM heap (40% of 16384MB + 10% of 12055MB rounded down)
            // 28439MB - 7759MB - 200MB = 20480MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(29820452864L));
            // 53669265408 bytes = 51183MB
            // We expect to be given 3 nodes as there are 3 AZs, so each will be 17061MB
            // 17061MB node => 6621MB JVM heap (40% of 16384MB + 10% of 677MB rounded down)
            // 17061MB - 6621MB - 200MB = 10240MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(53669265408L));
        }
        // auto is true with unknown jvm size, memory requirement above single node size, 1 AZ (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(100).getBytes() - 2 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(5).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            // 9296674816 bytes = 8866MB
            // 8866MB node => 3546MB JVM heap (40% of 8866MB rounded down)
            // 8866MB - 3546MB - 200MB = 5120MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(9296674816L));
            // 131222994944 bytes = 125178MB
            // 125144MB requirement => 2 nodes needed, each 62572MB
            // 62572MB node => 11172MB JVM heap (40% of 16384MB + 10% of 46188MB rounded down)
            // 62572MB - 11172MB - 200MB = 51200MB which is half of what we asked for for the tier
            // So with 2 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(131222994944L));
        }
        // auto is true with unknown jvm size, memory requirement above single node size, 2 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(100).getBytes() - 2 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(5).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                2
            );
            // 9296674816 bytes = 8866MB
            // 8866MB node => 3546MB JVM heap (40% of 8866MB rounded down)
            // 8866MB - 3546MB - 200MB = 5120MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(9296674816L));
            // 131222994944 bytes = 125178MB
            // We expect to be given 2 nodes as there are 2 AZs, so each will be 62572MB
            // 62572MB node => 11172MB JVM heap (40% of 16384MB + 10% of 46188MB rounded down)
            // 62572MB - 11172MB - 200MB = 51200MB which is half of what we asked for for the tier
            // So with 2 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(131222994944L));
        }
        // auto is true with unknown jvm size, memory requirement above single node size, 3 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(100).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(5).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                3
            );
            // 9296674816 bytes = 8866MB
            // 8866MB node => 3546MB JVM heap (40% of 8866MB rounded down)
            // 8866MB - 3546MB - 200MB = 5120MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(9296674816L));
            // 137183100930 bytes = 130828MB + 2 bytes
            // We expect to be given 3 nodes as there are 3 AZs, so each will be 43609 1/3MB
            // 43609 1/3MB node => 9276MB JVM heap (40% of 16384MB + 10% of 27225 1/3MB rounded down)
            // 43609 1/3MB - 9276MB - 200MB = 34133 1/3MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            // (The 2 byte discrepancy comes from the fact there are 3 nodes and 3 didn't divide exactly into the amount
            // of memory we needed, so each node gets a fraction of a byte extra to take it up to a whole number size)
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(137183100930L));
        }
        // auto is true with unknown jvm size, memory requirement above single node size, 1 AZ (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(155).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(50).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                1
            );
            // 65611497472 bytes = 62572MB
            // 62572MB node => 11172MB JVM heap (40% of 16384MB + 10% of 46188MB rounded down)
            // 62572MB - 11172MB - 200MB = 51200MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(65611497472L));
            // 202800889857 bytes = 193406MB + 1 byte
            // 193406MB requirement => 3 nodes needed, each 64468 2/3MB
            // 64468 2/3MB node => 11362MB JVM heap (40% of 16384MB + 10% of 48084 2/3MB rounded down)
            // 64468 2/3MB - 11362MB - 200MB = 52906 2/3MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            // (The 1 byte discrepancy comes from the fact there are 3 nodes and 3 didn't divide exactly into the amount
            // of memory we needed, so each node gets a fraction of a byte extra to take it up to a whole number size)
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(202800889857L));
        }
        // auto is true with unknown jvm size, memory requirement above single node size, 2 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(155).getBytes() - 4 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(50).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                2
            );
            // 65611497472 bytes = 62572MB
            // 62572MB node => 11172MB JVM heap (40% of 16384MB + 10% of 46188MB rounded down)
            // 62572MB - 11172MB - 200MB = 51200MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(65611497472L));
            // 208758898688 bytes = 199088MB
            // We expect to be given a multiple of 2 nodes as there are 2 AZs
            // 199088MB requirement => 4 nodes needed, each 49772MB
            // 49772MB node => 9892MB JVM heap (40% of 16384MB + 10% of 33388MB rounded down)
            // 49772MB - 9892MB - 200MB = 39680MB which is one quarter of what we asked for for the tier
            // So with 4 nodes of this size we'll have the requested amount
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(208758898688L));
        }
        // auto is true with unknown jvm size, memory requirement above single node size, 3 AZs (this is a realistic case for Cloud)
        {
            capacity = new NativeMemoryCapacity(
                ByteSizeValue.ofGb(155).getBytes() - 3 * NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes(),
                ByteSizeValue.ofGb(50).getBytes() - NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes()
            );
            AutoscalingCapacity autoscalingCapacity = capacity.autoscalingCapacity(
                randomIntBetween(5, 90),
                true,
                AUTO_ML_MEMORY_FOR_64GB_NODE,
                3
            );
            // 65611497472 bytes = 62572MB
            // 62572MB node => 11172MB JVM heap (40% of 16384MB + 10% of 46188MB rounded down)
            // 62572MB - 11172MB - 200MB = 51200MB which is what we need on a single node
            assertThat(autoscalingCapacity.node().memory().getBytes(), equalTo(65611497472L));
            // 202800889857 bytes = 193406MB + 1 byte
            // We expect to be given 3 nodes as there are 3 AZs, so each will be 64468 2/3MB
            // 64468 2/3MB node => 11362MB JVM heap (40% of 16384MB + 10% of 48084 2/3MB rounded down)
            // 64468 2/3MB - 11362MB - 200MB = 52906 2/3MB which is one third of what we asked for for the tier
            // So with 3 nodes of this size we'll have the requested amount
            // (The 1 byte discrepancy comes from the fact there are 3 nodes and 3 didn't divide exactly into the amount
            // of memory we needed, so each node gets a fraction of a byte extra to take it up to a whole number size)
            assertThat(autoscalingCapacity.total().memory().getBytes(), equalTo(202800889857L));
        }
    }

    public void testAutoscalingCapacityConsistency() {
        final BiConsumer<NativeMemoryCapacity, Integer> consistentAutoAssertions = (nativeMemory, memoryPercentage) -> {
            AutoscalingCapacity autoscalingCapacity = nativeMemory.autoscalingCapacity(25, true, Long.MAX_VALUE, 1);
            assertThat(
                autoscalingCapacity.total().memory().getBytes(),
                greaterThan(nativeMemory.getTierMlNativeMemoryRequirementExcludingOverhead())
            );
            assertThat(
                autoscalingCapacity.node().memory().getBytes(),
                greaterThan(nativeMemory.getNodeMlNativeMemoryRequirementExcludingOverhead())
            );
            assertThat(
                autoscalingCapacity.total().memory().getBytes(),
                greaterThanOrEqualTo(autoscalingCapacity.node().memory().getBytes())
            );
        };

        { // 0 memory
            assertThat(
                NativeMemoryCalculator.calculateApproxNecessaryNodeSize(
                    0L,
                    randomLongBetween(0L, ByteSizeValue.ofGb(100).getBytes()),
                    randomIntBetween(0, 100),
                    randomBoolean()
                ),
                equalTo(0L)
            );
            assertThat(
                NativeMemoryCalculator.calculateApproxNecessaryNodeSize(0L, null, randomIntBetween(0, 100), randomBoolean()),
                equalTo(0L)
            );
        }
        for (int i = 0; i < NUM_TEST_RUNS; i++) {
            int memoryPercentage = randomIntBetween(5, 200);
            { // tiny memory
                long nodeMemory = randomLongBetween(ByteSizeValue.ofKb(100).getBytes(), ByteSizeValue.ofMb(500).getBytes());
                consistentAutoAssertions.accept(
                    new NativeMemoryCapacity(randomLongBetween(nodeMemory, nodeMemory * 4), nodeMemory),
                    memoryPercentage
                );
            }
            { // normal-ish memory
                long nodeMemory = randomLongBetween(ByteSizeValue.ofMb(500).getBytes(), ByteSizeValue.ofGb(4).getBytes());
                consistentAutoAssertions.accept(
                    new NativeMemoryCapacity(randomLongBetween(nodeMemory, nodeMemory * 4), nodeMemory),
                    memoryPercentage
                );
            }
            { // huge memory
                long nodeMemory = randomLongBetween(ByteSizeValue.ofGb(30).getBytes(), ByteSizeValue.ofGb(60).getBytes());
                consistentAutoAssertions.accept(
                    new NativeMemoryCapacity(randomLongBetween(nodeMemory, nodeMemory * 4), nodeMemory),
                    memoryPercentage
                );
            }
        }
    }

}
