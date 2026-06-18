package com.forgeshift.wso2.migration.translator;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SiddhiThrottleTranslatorTest {

    private static String policy(String window, String threshold) {
        return "FROM EligibilityStream[isEligible==true]#throttler:" + window + "\n"
                + "SELECT throttleKey, (" + threshold + ") as isThrottled, throttleKey as throttleKey\n"
                + "INSERT ALL EVENTS INTO ResultStream;";
    }

    @Test
    void canonicalPerMinuteIdiom_mapsToRateLimitingMinute() {
        Optional<Map<String, Object>> cfg =
                SiddhiThrottleTranslator.toRateLimitConfig(policy("timeBatch(1 min)", "count(messageID) >= 5"));
        assertThat(cfg).isPresent();
        assertThat(cfg.get()).containsEntry("minute", 5).containsEntry("policy", "local");
    }

    @Test
    void perHourIdiom_mapsToRateLimitingHour() {
        Optional<Map<String, Object>> cfg =
                SiddhiThrottleTranslator.toRateLimitConfig(policy("timeBatch(1 hour)", "count(messageID) >= 1000"));
        assertThat(cfg).isPresent();
        assertThat(cfg.get()).containsEntry("hour", 1000);
    }

    @Test
    void sixtySeconds_normalisesToMinute() {
        Optional<Map<String, Object>> cfg =
                SiddhiThrottleTranslator.toRateLimitConfig(policy("timeBatch(60 sec)", "count(id) >= 100"));
        assertThat(cfg).isPresent();
        assertThat(cfg.get()).containsEntry("minute", 100);
    }

    @Test
    void nonExpressibleWindow_fiveMinutes_isNotRecognised() {
        assertThat(SiddhiThrottleTranslator.toRateLimitConfig(policy("timeBatch(5 min)", "count(id) >= 5"))).isEmpty();
    }

    @Test
    void noCountThreshold_isNotRecognised() {
        assertThat(SiddhiThrottleTranslator.toRateLimitConfig(
                "FROM S#throttler:timeBatch(1 min) SELECT x INSERT INTO R;")).isEmpty();
    }

    @Test
    void multipleWindows_areAmbiguous_andRefused() {
        String two = "FROM A#throttler:timeBatch(1 min) SELECT (count(id) >= 5) as t INSERT INTO B;\n"
                + "FROM C#throttler:timeBatch(1 hour) SELECT (count(id) >= 100) as t INSERT INTO D;";
        assertThat(SiddhiThrottleTranslator.toRateLimitConfig(two)).isEmpty();
    }

    @Test
    void blankOrNull_isEmpty() {
        assertThat(SiddhiThrottleTranslator.toRateLimitConfig(null)).isEmpty();
        assertThat(SiddhiThrottleTranslator.toRateLimitConfig("   ")).isEmpty();
    }

    // ---- toRequestsPerMinute (the form ThrottlingTierResolver consumes) ----

    @Test
    void rpm_perMinute_isTheLimitItself() {
        assertThat(SiddhiThrottleTranslator.toRequestsPerMinute(policy("timeBatch(1 min)", "count(id) >= 5")))
                .contains(5);
    }

    @Test
    void rpm_perHour_normalisesDown() {
        assertThat(SiddhiThrottleTranslator.toRequestsPerMinute(policy("timeBatch(1 hour)", "count(id) >= 1200")))
                .contains(20);   // 1200 / 60
    }

    @Test
    void rpm_perSecond_normalisesUp() {
        assertThat(SiddhiThrottleTranslator.toRequestsPerMinute(policy("timeBatch(1 sec)", "count(id) >= 10")))
                .contains(600);  // 10 * 60
    }

    @Test
    void rpm_handlesNonKongWindow_fiveMinutes() {
        // toRateLimitConfig refuses 5 min (no exact Kong period), but rpm still normalises it.
        assertThat(SiddhiThrottleTranslator.toRequestsPerMinute(policy("timeBatch(5 min)", "count(id) >= 100")))
                .contains(20);   // 100 / 5
    }

    @Test
    void rpm_emptyWhenNotTheIdiom() {
        assertThat(SiddhiThrottleTranslator.toRequestsPerMinute("FROM S SELECT x INSERT INTO R;")).isEmpty();
    }
}
