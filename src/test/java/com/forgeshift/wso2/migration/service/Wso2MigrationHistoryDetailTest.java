package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.dto.Wso2MigrationHistoryDetailRecord;
import com.forgeshift.wso2.migration.dto.Wso2MigrationHistoryDetailResponse;
import com.forgeshift.wso2.migration.domain.EntityMapping;
import com.forgeshift.wso2.migration.repository.EntityMappingRepository;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import com.forgeshift.wso2.migration.repository.MigrationReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The history-detail drill-down must show per-RESOURCE rows (names + what they became in Kong),
 * not just per-type counts. Previously the record carried only counts, so the UI showed "N/A" for
 * every resource name. These tests pin the enrichment sourced from the report's apiKongDetails +
 * dependencyMigrations.
 */
class Wso2MigrationHistoryDetailTest {

    private final MigrationJobRepository jobRepo = mock(MigrationJobRepository.class);
    private final MigrationReportRepository reportRepo = mock(MigrationReportRepository.class);
    private final EntityMappingRepository entityRepo = mock(EntityMappingRepository.class);
    private final Wso2MigrationHistoryService service =
            new Wso2MigrationHistoryService(jobRepo, reportRepo, entityRepo);

    private static final String TXN = "LCMO_probestack_carbon.super_20260715191806865";

    {
        // default: no entity mappings unless a test provides them
        when(entityRepo.findByMigrationJobId(any())).thenReturn(List.of());
    }

    private MigrationJob jobWith(MigrationJob.ResourceProgress... typeProgress) {
        var progress = new java.util.HashMap<String, MigrationJob.ResourceProgress>();
        progress.put("apis", typeProgress.length > 0 ? typeProgress[0] : null);
        return MigrationJob.builder()
                .id("job-1").requestTransactionId(TXN)
                .companyName("probestack").wso2Tenant("carbon.super")
                .controlPlaneId("d7680a45")
                .resourceProgress(progress)
                .build();
    }

    @Test
    void detail_expandsApisIntoNamedResourcesWithKongEntities() {
        when(jobRepo.findByRequestTransactionId(TXN)).thenReturn(List.of(jobWith(
                MigrationJob.ResourceProgress.builder().state("COMPLETED").translated(1).deployed(1).build())));

        MigrationReport report = MigrationReport.builder()
                .migrationJobId("job-1")
                .outcomes(List.of(MigrationReport.ResourceOutcome.builder()
                        .resourceType("apis").translated(1).deployed(1).failed(0)
                        .failedSourceIds(List.of()).build()))
                .apiKongDetails(List.of(MigrationReport.ApiKongDetail.builder()
                        .wso2SourceId("c1023ba3").wso2SourceName("BodyTransformApi")
                        .kongServiceName("bodytransformapi-1-0-0")
                        .routePaths(List.of("/transform/1.0.0/post"))
                        .plugins(List.of("forgeshift-json-xml", "rate-limiting", "jwt"))
                        .build()))
                .build();
        when(reportRepo.findByMigrationJobId("job-1")).thenReturn(Optional.of(report));

        Wso2MigrationHistoryDetailResponse resp = service.getMigrationHistoryDetail(TXN, null);
        var apiRec = resp.getRecords().stream()
                .filter(r -> "apis".equals(r.getResourceType())).findFirst().orElseThrow();

        assertThat(apiRec.getResources()).hasSize(1);
        Wso2MigrationHistoryDetailRecord.ResourceItem it = apiRec.getResources().get(0);
        assertThat(it.getSourceName()).isEqualTo("BodyTransformApi");   // no more "N/A"
        assertThat(it.getKongServiceName()).isEqualTo("bodytransformapi-1-0-0");
        assertThat(it.getRoutePaths()).containsExactly("/transform/1.0.0/post");
        assertThat(it.getPlugins()).contains("forgeshift-json-xml");
        assertThat(it.getStatus()).isEqualTo("MIGRATED");
    }

    @Test
    void detail_marksFailedAndAlreadyInKongFromReport() {
        when(jobRepo.findByRequestTransactionId(TXN)).thenReturn(List.of(jobWith(
                MigrationJob.ResourceProgress.builder().state("COMPLETED").build())));

        MigrationReport report = MigrationReport.builder()
                .migrationJobId("job-1")
                .outcomes(List.of(MigrationReport.ResourceOutcome.builder()
                        .resourceType("apis").failed(1)
                        .failedSourceIds(List.of("bad-api")).build()))
                .apiKongDetails(List.of(
                        MigrationReport.ApiKongDetail.builder()
                                .wso2SourceId("bad-api").wso2SourceName("BrokenApi").build()))
                .dependencyMigrations(List.of(
                        MigrationReport.DependencyMigration.builder()
                                .resourceType("apis").wso2SourceId("kept-api").name("UnchangedApi")
                                .alreadyInKong(true).build()))
                .warnings(List.of(MigrationReport.Warning.builder()
                        .wso2SourceId("bad-api").message("mutual-TLS needs manual review").build()))
                .build();
        when(reportRepo.findByMigrationJobId("job-1")).thenReturn(Optional.of(report));

        var apiRec = service.getMigrationHistoryDetail(TXN, null).getRecords().stream()
                .filter(r -> "apis".equals(r.getResourceType())).findFirst().orElseThrow();

        var byName = apiRec.getResources().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Wso2MigrationHistoryDetailRecord.ResourceItem::getSourceName, r -> r));
        assertThat(byName.get("BrokenApi").getStatus()).isEqualTo("FAILED");
        assertThat(byName.get("BrokenApi").getWarning()).contains("manual review");
        assertThat(byName.get("UnchangedApi").getStatus()).isEqualTo("UNCHANGED");
    }

    @Test
    void detail_attachesKongEntityIdFromMappings_andGitCommitUrl() {
        when(jobRepo.findByRequestTransactionId(TXN)).thenReturn(List.of(jobWith(
                MigrationJob.ResourceProgress.builder().state("COMPLETED").deployed(1).build())));
        when(reportRepo.findByMigrationJobId("job-1")).thenReturn(Optional.of(MigrationReport.builder()
                .migrationJobId("job-1")
                .gitRepo("acme/acme-kong-config").gitBranch("main")
                .gitCommitSha("21b78e2").gitCommitUrl("https://github.com/acme/acme-kong-config/commit/21b78e2")
                .outcomes(List.of(MigrationReport.ResourceOutcome.builder()
                        .resourceType("apis").deployed(1).failedSourceIds(List.of()).build()))
                .apiKongDetails(List.of(MigrationReport.ApiKongDetail.builder()
                        .wso2SourceId("c1023ba3").wso2SourceName("BodyTransformApi")
                        .kongServiceName("bodytransformapi-1-0-0").build()))
                .build()));
        // #2: this API became a Kong service + 1 route
        when(entityRepo.findByMigrationJobId("job-1")).thenReturn(List.of(
                EntityMapping.builder().wso2SourceId("c1023ba3").kongEntityType("ROUTE").kongUuid("route-uuid").build(),
                EntityMapping.builder().wso2SourceId("c1023ba3").kongEntityType("SERVICE").kongUuid("svc-uuid-123").build()));

        var apiRec = service.getMigrationHistoryDetail(TXN, null).getRecords().stream()
                .filter(r -> "apis".equals(r.getResourceType())).findFirst().orElseThrow();

        // #3 git commit link is on the record
        assertThat(apiRec.getGitCommitUrl()).contains("/commit/21b78e2");
        // #2 primary Kong id is the SERVICE (not the route), with the full entity count
        var it = apiRec.getResources().get(0);
        assertThat(it.getKongEntityId()).isEqualTo("svc-uuid-123");
        assertThat(it.getKongEntityType()).isEqualTo("SERVICE");
        assertThat(it.getKongEntityCount()).isEqualTo(2);
    }

    @Test
    void detail_oldRunWithoutReportDetails_leavesResourcesEmpty_countsStillPresent() {
        when(jobRepo.findByRequestTransactionId(TXN)).thenReturn(List.of(jobWith(
                MigrationJob.ResourceProgress.builder().state("COMPLETED").translated(3).deployed(3).build())));
        when(reportRepo.findByMigrationJobId("job-1")).thenReturn(Optional.empty());

        var rec = service.getMigrationHistoryDetail(TXN, null).getRecords().get(0);
        assertThat(rec.getResources()).isEmpty();      // graceful — UI shows the counts rollup
        assertThat(rec.getDeployed()).isEqualTo(3);
    }
}
