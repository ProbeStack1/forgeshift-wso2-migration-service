package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.EntityMapping;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.repository.EntityMappingRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the WSO2→Kong {@code entity_mappings} AFTER a decK apply, from the two
 * JSON outputs decK produces:
 * <ul>
 *   <li><b>{@code deck gateway dump --select-tag ... --format json}</b> → every deployed
 *       entity with its {@code id} and parent (route→service, plugin→service/route/consumer,
 *       target→upstream). The {@code wso2-source-id:<uuid>} tag links it back to its WSO2 source.</li>
 *   <li><b>{@code deck gateway apply --json-output}</b> → the {@code errors[]} array
 *       (which entity failed and why).</li>
 * </ul>
 *
 * <p>Tolerant of both nested (decK declarative) and flat shapes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeckResultMapper {

    private static final String NO_PARENT = "_";

    private final EntityMappingRepository mappingRepo;
    private final MigrationProperties props;

    @Data
    @Builder
    public static class Summary {
        private int mapped;
        private int errors;
        private List<String> failedDetails;
    }

    public Summary ingest(MigrationJob job, Map<String, Object> kongState, Map<String, Object> applyReport) {
        String prefix = props.getTranslation().getTagPrefix() + ":";
        String cpId = job.getControlPlaneId();
        Instant now = Instant.now();
        List<EntityMapping> rows = new ArrayList<>();

        for (Map<String, Object> svc : list(kongState, "services")) {
            String svcId = str(svc.get("id"));
            addRow(rows, job, cpId, "SERVICE", svc, NO_PARENT, prefix, now);
            for (Map<String, Object> r : list(svc, "routes")) {
                String rId = str(r.get("id"));
                addRow(rows, job, cpId, "ROUTE", r, svcId, prefix, now);
                for (Map<String, Object> p : list(r, "plugins")) {
                    addRow(rows, job, cpId, "PLUGIN", p, rId, prefix, now);
                }
            }
            for (Map<String, Object> p : list(svc, "plugins")) {
                addRow(rows, job, cpId, "PLUGIN", p, svcId, prefix, now);
            }
        }
        for (Map<String, Object> c : list(kongState, "consumers")) {
            String cId = str(c.get("id"));
            addRow(rows, job, cpId, "CONSUMER", c, NO_PARENT, prefix, now);
            for (Map<String, Object> p : list(c, "plugins")) {
                addRow(rows, job, cpId, "PLUGIN", p, cId, prefix, now);
            }
        }
        for (Map<String, Object> u : list(kongState, "upstreams")) {
            String uId = str(u.get("id"));
            addRow(rows, job, cpId, "UPSTREAM", u, NO_PARENT, prefix, now);
            for (Map<String, Object> t : list(u, "targets")) {
                addRow(rows, job, cpId, "TARGET", t, uId, prefix, now);
            }
        }
        for (Map<String, Object> cc : list(kongState, "ca_certificates")) {
            addRow(rows, job, cpId, "CA_CERTIFICATE", cc, NO_PARENT, prefix, now);
        }
        // Tolerate flat top-level arrays (when the dump isn't nested under the parent).
        for (Map<String, Object> p : list(kongState, "plugins")) {
            addRow(rows, job, cpId, "PLUGIN", p, NO_PARENT, prefix, now);
        }
        for (Map<String, Object> r : list(kongState, "routes")) {
            addRow(rows, job, cpId, "ROUTE", r, NO_PARENT, prefix, now);
        }

        if (!rows.isEmpty()) {
            mappingRepo.saveAll(rows);
        }

        List<String> failed = extractErrors(applyReport);
        log.info("decK result ingested for job {} — {} mappings written, {} apply error(s)",
                job.getId(), rows.size(), failed.size());
        return Summary.builder()
                .mapped(rows.size())
                .errors(failed.size())
                .failedDetails(failed)
                .build();
    }

    private void addRow(List<EntityMapping> rows, MigrationJob job, String cpId, String type,
                        Map<String, Object> entity, String parent, String prefix, Instant now) {
        String id = str(entity.get("id"));
        if (!StringUtils.hasText(id)) return;
        String source = sourceFromTags(entity.get("tags"), prefix);
        if (!StringUtils.hasText(source)) return;   // not one of ours — skip
        String parentKey = parent == null ? NO_PARENT : parent;
        rows.add(EntityMapping.builder()
                .id(cpId + "|" + source + "|" + type + "|" + parentKey)
                .companyName(job.getCompanyName())
                .wso2Tenant(job.getWso2Tenant())
                .controlPlaneId(cpId)
                .wso2SourceId(source)
                .kongEntityType(type)
                .kongUuid(id)
                .parentKongUuid(parentKey)
                .tags(stringList(entity.get("tags")))
                .migrationJobId(job.getId())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ---------------- helpers ----------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> root, String key) {
        if (root == null) return List.of();
        Object v = root.get(key);
        if (!(v instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> stringList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : l) {
            if (x != null) out.add(String.valueOf(x));
        }
        return out;
    }

    private static String sourceFromTags(Object tags, String prefix) {
        for (String t : stringList(tags)) {
            if (t.startsWith(prefix)) return t.substring(prefix.length());
        }
        return null;
    }

    private static List<String> extractErrors(Map<String, Object> applyReport) {
        List<String> out = new ArrayList<>();
        if (applyReport == null) return out;
        Object errs = applyReport.get("errors");
        if (errs instanceof List<?> l) {
            for (Object e : l) out.add(String.valueOf(e));
        }
        return out;
    }
}
