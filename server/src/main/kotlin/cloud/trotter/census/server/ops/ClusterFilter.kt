package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.ingest.WireGrammars

data class ClusterFilter(val platform: String, val version: String?, val status: String?, val page: Int, val kind: SkeletonKind? = null) {
    fun href(status: String? = this.status, page: Int? = null, kind: SkeletonKind? = this.kind): String =
        "/ops/clusters/view?platform=$platform&version=${version ?: "none"}" +
            (kind?.let { "&kind=${it.wire}" } ?: "") + (status?.let { "&status=$it" } ?: "") + (page?.let { "&page=$it" } ?: "")

    companion object {
        fun parse(platform: String?, version: String?, status: String?, page: String?, kind: String? = null): ClusterFilter? {
            val parsedKind = kind?.let { SkeletonKind.fromWire(it) ?: return null }
            val currentPage = if (page == null) 1 else page.toIntOrNull() ?: return null
            if (platform == null || !WireGrammars.platform.matches(platform) ||
                version == null || (version != "none" && !WireGrammars.platformAppVersion.matches(version)) ||
                (status != null && status !in CLUSTER_STATUSES) || currentPage < 1
            ) return null
            return ClusterFilter(platform, version.takeUnless { it == "none" }, status, currentPage, parsedKind)
        }
    }
}
