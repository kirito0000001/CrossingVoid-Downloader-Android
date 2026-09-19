package com.lingjing.launcher.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 版本号比较决定"要不要更新启动器"，比错会让用户被反复提示或用上旧包。
 */
public class LauncherUpdateVerifierTest {
    @Test
    public void comparesEachSegmentNumerically() {
        assertTrue(LauncherUpdateVerifier.compareVersionNames("1.0.25", "1.0.9") > 0);
        assertTrue(LauncherUpdateVerifier.compareVersionNames("1.0.9", "1.0.25") < 0);
        assertTrue(LauncherUpdateVerifier.compareVersionNames("2.0", "1.9.9") > 0);
        assertEquals(0, LauncherUpdateVerifier.compareVersionNames("1.0.25", "1.0.25"));
    }

    @Test
    public void treatsMissingAndNonNumericSegmentsAsZero() {
        assertEquals(0, LauncherUpdateVerifier.compareVersionNames("1.0", "1.0.0"));
        assertEquals(0, LauncherUpdateVerifier.compareVersionNames("1.x", "1.0"));
        assertTrue(LauncherUpdateVerifier.compareVersionNames("1.0.1", "1.0") > 0);
    }

    @Test
    public void toleratesNullVersionNames() {
        assertEquals(0, LauncherUpdateVerifier.compareVersionNames(null, ""));
        assertTrue(LauncherUpdateVerifier.compareVersionNames("1.0", null) > 0);
    }
}
