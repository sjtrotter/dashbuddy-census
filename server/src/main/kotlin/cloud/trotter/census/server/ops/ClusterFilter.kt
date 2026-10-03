package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.ingest.WireGrammars

data class ClusterFilter(val platform: String, val version: String?, val status: String?, val page: Int) {
    fun href(status: String? = this.status, page: Int? = null): String =
        "/ops/clusters/view?platform=$platform&version=${version ?: "none"}" +
            (status?.let { "&status=$it" } ?: "") + (page?.let { "&page=$it" } ?: "")

    companion object {
        fun parse(platform: String?, version: String?, status: String?, page: String?): ClusterFilter? {
            val currentPage = if (page == null) 1 else page.toIntOrNull() ?: return null
            if (platform == null || !WireGrammars.platform.matches(platform) ||
                version == null || (version != "none" && !WireGrammars.platformAppVersion.matches(version)) ||
                (status != null && status !in CLUSTER_STATUSES) || currentPage < 1
            ) return null
            return ClusterFilter(platform, version.takeUnless { it == "none" }, status, currentPage)
        }
    }
}
