/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.test.ESTestCase;

import java.util.Locale;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.instanceOf;

public class DiskThresholdSettingsTests extends ESTestCase {

    public void testDefaults() {
        ClusterSettings nss = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        DiskThresholdSettings diskThresholdSettings = new DiskThresholdSettings(Settings.EMPTY, nss);

        ByteSizeValue zeroBytes = ByteSizeValue.parseBytesSizeValue("0b", "test");
        assertEquals(zeroBytes, diskThresholdSettings.getFreeBytesThresholdHigh());
        assertEquals(10.0D, diskThresholdSettings.getFreeDiskThresholdHigh(), 0.0D);
        assertEquals(zeroBytes, diskThresholdSettings.getFreeBytesThresholdLow());
        assertEquals(15.0D, diskThresholdSettings.getFreeDiskThresholdLow(), 0.0D);
        assertEquals(60L, diskThresholdSettings.getRerouteInterval().seconds());
        assertTrue(diskThresholdSettings.isEnabled());
        assertEquals(zeroBytes, diskThresholdSettings.getFreeBytesThresholdFloodStage());
        assertEquals(5.0D, diskThresholdSettings.getFreeDiskThresholdFloodStage(), 0.0D);
    }

    public void testUpdate() {
        ClusterSettings nss = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        DiskThresholdSettings diskThresholdSettings = new DiskThresholdSettings(Settings.EMPTY, nss);

        Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), false)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "500mb")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "1000mb")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "250mb")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.getKey(), "30s")
            .build();
        nss.applySettings(newSettings);

        assertEquals(ByteSizeValue.parseBytesSizeValue("500mb", "test"), diskThresholdSettings.getFreeBytesThresholdHigh());
        assertEquals(0.0D, diskThresholdSettings.getFreeDiskThresholdHigh(), 0.0D);
        assertEquals(ByteSizeValue.parseBytesSizeValue("1000mb", "test"), diskThresholdSettings.getFreeBytesThresholdLow());
        assertEquals(0.0D, diskThresholdSettings.getFreeDiskThresholdLow(), 0.0D);
        assertEquals(ByteSizeValue.parseBytesSizeValue("250mb", "test"), diskThresholdSettings.getFreeBytesThresholdFloodStage());
        assertEquals(0.0D, diskThresholdSettings.getFreeDiskThresholdFloodStage(), 0.0D);
        assertEquals(30L, diskThresholdSettings.getRerouteInterval().seconds());
        assertFalse(diskThresholdSettings.isEnabled());
    }

    public void testInvalidConstruction() {
        final Settings settings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "90%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "80%")
            .build();
        final ClusterSettings clusterSettings = new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        final IllegalArgumentException cause = expectThrows(
            IllegalArgumentException.class,
            () -> new DiskThresholdSettings(settings, clusterSettings)
        );
        final String expectedCause = String.format(
            "setting [%s=90%%] cannot be greater than [%s=80%%]",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(),
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()
        );
        assertThat(cause, hasToString(containsString(expectedCause)));
    }

    public void testInvalidLowHighPercentageUpdate() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "90%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "80%")
            .build();

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> clusterSettings.applySettings(newSettings));
        final String expected = "illegal value can't update [cluster.routing.allocation.disk.watermark.low] from [85%] to [90%]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String expectedCause = String.format(
            "setting [%s=90%%] cannot be greater than [%s=80%%]",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(),
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()
        );
        assertThat(cause, hasToString(containsString(expectedCause)));
    }

    public void testInvalidHighFloodPercentageUpdate() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "50%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "60%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "55%")
            .build();

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> clusterSettings.applySettings(newSettings));
        final String expected = "illegal value can't update [cluster.routing.allocation.disk.watermark.low] from [85%] to [50%]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String expectedCause = String.format(
            "setting [%s=60%%] cannot be greater than [%s=55%%]",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(),
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey()
        );
        assertThat(cause, hasToString(containsString(expectedCause)));
    }

    public void testInvalidLowHighBytesUpdate() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "500m")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "1000m")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "250m")
            .build();

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> clusterSettings.applySettings(newSettings));
        final String expected = "illegal value can't update [cluster.routing.allocation.disk.watermark.low] from [85%] to [500m]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String expectedCause = String.format(
            "setting [%s=500m] cannot be less than [%s=1000m]",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(),
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()
        );
        assertThat(cause, hasToString(containsString(expectedCause)));
    }

    public void testInvalidHighFloodBytesUpdate() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "500m")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "1000m")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "750m")
            .build();

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> clusterSettings.applySettings(newSettings));
        final String expected = "illegal value can't update [cluster.routing.allocation.disk.watermark.low] from [85%] to [500m]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String expectedCause = String.format(
            "setting [%s=500m] cannot be less than [%s=1000m]",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(),
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()
        );
        assertThat(cause, hasToString(containsString(expectedCause)));
    }

    public void testIncompatibleThresholdUpdate() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "90%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "1000m")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "95%")
            .build();

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> clusterSettings.applySettings(newSettings));
        final String expected = "illegal value can't update [cluster.routing.allocation.disk.watermark.low] from [85%] to [90%]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String incompatibleExpected = String.format(
            Locale.ROOT,
            "unable to consistently parse [%s=%s], [%s=%s], [%s=%s] as percentage or bytes",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(),
            "90%",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(),
            "1000m",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(),
            "95%"
        );
        assertThat(cause, hasToString(containsString(incompatibleExpected)));
    }

    public void testInvalidHighDiskThreshold() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings newSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "75%")
            .build();

        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> clusterSettings.applySettings(newSettings));
        final String expected = "illegal value can't update [cluster.routing.allocation.disk.watermark.high] from [90%] to [75%]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String expectedCause = String.format(
            "setting [%s=85%%] cannot be greater than [%s=75%%]",
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(),
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()
        );
        assertThat(cause, hasToString(containsString(expectedCause)));
    }

    public void testSequenceOfUpdates() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        new DiskThresholdSettings(Settings.EMPTY, clusterSettings); // this has the effect of registering the settings updater

        final Settings.Builder target = Settings.builder();

        {
            final Settings settings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "99%")
                .build();
            final Settings.Builder updates = Settings.builder();
            assertTrue(clusterSettings.updateSettings(settings, target, updates, "persistent"));
            assertNull(target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey()));
            assertNull(target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()));
            assertThat(
                target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey()),
                equalTo("99%")
            );
        }

        {
            final Settings settings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "97%")
                .build();
            final Settings.Builder updates = Settings.builder();
            assertTrue(clusterSettings.updateSettings(settings, target, updates, "persistent"));
            assertNull(target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey()));
            assertThat(target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()), equalTo("97%"));
            assertThat(
                target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey()),
                equalTo("99%")
            );
        }

        {
            final Settings settings = Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "95%")
                .build();
            final Settings.Builder updates = Settings.builder();
            assertTrue(clusterSettings.updateSettings(settings, target, updates, "persistent"));
            assertThat(target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey()), equalTo("95%"));
            assertThat(target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey()), equalTo("97%"));
            assertThat(
                target.get(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey()),
                equalTo("99%")
            );
        }
    }

    public void testThresholdDescriptions() {
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);

        DiskThresholdSettings diskThresholdSettings = new DiskThresholdSettings(Settings.EMPTY, clusterSettings);
        assertThat(diskThresholdSettings.describeLowThreshold(), equalTo("85%"));
        assertThat(diskThresholdSettings.describeHighThreshold(), equalTo("90%"));
        assertThat(diskThresholdSettings.describeFloodStageThreshold(), equalTo("95%"));

        diskThresholdSettings = new DiskThresholdSettings(
            Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "91.2%")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "91.3%")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "91.4%")
                .build(),
            clusterSettings
        );

        assertThat(diskThresholdSettings.describeLowThreshold(), equalTo("91.2%"));
        assertThat(diskThresholdSettings.describeHighThreshold(), equalTo("91.3%"));
        assertThat(diskThresholdSettings.describeFloodStageThreshold(), equalTo("91.4%"));

        diskThresholdSettings = new DiskThresholdSettings(
            Settings.builder()
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "1GB")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "10MB")
                .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "1B")
                .build(),
            clusterSettings
        );

        assertThat(diskThresholdSettings.describeLowThreshold(), equalTo("1gb"));
        assertThat(diskThresholdSettings.describeHighThreshold(), equalTo("10mb"));
        assertThat(diskThresholdSettings.describeFloodStageThreshold(), equalTo("1b"));
    }

}
