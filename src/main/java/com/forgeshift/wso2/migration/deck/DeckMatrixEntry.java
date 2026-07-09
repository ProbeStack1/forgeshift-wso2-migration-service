package com.forgeshift.wso2.migration.deck;

/**
 * One pipeline-matrix leg of a migration bundle: the decK path the leg applies and the
 * comma-separated {@code trackingId}s of the resources that path carries. The generated
 * workflow embeds these as matrix variables, and each leg reports its outcome to
 * {@code POST /wso2/migration-status?migrationId=…&trackingIds=<tracking>}.
 *
 * @param path     repo-relative decK file the leg applies (e.g. {@code kong/dev/api-x.yaml})
 * @param tracking comma-separated trackingIds resolved by this leg's apply
 */
public record DeckMatrixEntry(String path, String tracking) {
}
