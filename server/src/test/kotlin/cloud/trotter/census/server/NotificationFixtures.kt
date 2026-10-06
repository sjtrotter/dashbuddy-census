package cloud.trotter.census.server

import cloud.trotter.census.contract.*
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import kotlinx.serialization.json.*
import java.time.LocalDate

internal val notificationPolicy = Policy(acceptedSchemaIds = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS)
internal fun notificationFixture(
    day: LocalDate = LocalDate.of(2026, 10, 2), channel: String = "CHANNEL_SENTINEL",
    platform: String = "doordash", version: String = "8.0", hash: String = "0123456789abcdef",
): JsonObject {
    val slots = NotifTextField.entries.associateWith { TextSlot(hash, "words:1") }
    val item = NotificationSkeletonDto(SkeletonKind.NOTIFICATION, NotificationSkeletonSchema.SCHEMA_ID, 1, 1,
        requireNotNull(CensusFingerprint.of(platform, channel, slots)), platform, platformAppVersion = version,
        engineVersion = 1, day = day.toString(), channelId = channel, slots = slots)
    return Json.parseToJsonElement(NotificationSkeletonSchema.serialize(item)).jsonObject
}
internal fun notificationAccepted(day: LocalDate = LocalDate.of(2026, 10, 2), channel: String = "CHANNEL_SENTINEL"): ItemVerdict.Accepted =
    SkeletonValidator.validate(notificationFixture(day, channel), notificationPolicy, day) as ItemVerdict.Accepted
