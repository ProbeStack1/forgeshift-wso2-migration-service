package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Safety net for the two-stage deck deploy. After a migration pushes its bundle it parks the job in
 * {@link MigrationState#DEPLOYING_TO_KONG} and waits for the pipeline to POST the apply result to
 * {@code /migrations/{id}/deck-result}. If that callback never arrives (e.g. the pipeline runner
 * died, or the callback step isn't wired), the job would wait forever — so this sweep flips any
 * DEPLOYING_TO_KONG job that's been waiting longer than {@code deck.apply-timeout-minutes} to
 * {@link MigrationState#TIMED_OUT} with a clear message to go check the pipeline run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeckApplyTimeoutSweeper {

    private final MigrationJobRepository jobRepository;
    private final MigrationProperties props;

    /** Runs every minute; cheap (indexed query on state + updatedAt). */
    @Scheduled(fixedDelayString = "${forgeshift.migration.deck.apply-timeout-sweep-ms:60000}")
    public void sweep() {
        int timeoutMin = props.getDeck().getApplyTimeoutMinutes();
        if (timeoutMin <= 0) return;   // disabled
        Instant cutoff = Instant.now().minus(timeoutMin, ChronoUnit.MINUTES);
        List<MigrationJob> stuck = jobRepository.findByStateAndUpdatedAtBefore(
                MigrationState.DEPLOYING_TO_KONG, cutoff);
        if (stuck.isEmpty()) return;
        for (MigrationJob job : stuck) {
            job.setState(MigrationState.TIMED_OUT);
            job.setCompletedAt(Instant.now());
            job.setLastError("No deck-apply callback received within " + timeoutMin
                    + " min — check the GitHub Actions pipeline run for this migration.");
            jobRepository.save(job);
            log.warn("Migration job {} → TIMED_OUT (no deck-apply callback after {} min)", job.getId(), timeoutMin);
        }
    }
}
