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

/**
 * #78: a `notification.skeleton.v2` item. [shapeField] carries a money shape (v1 sends that value as `mixed`), and
 * [actions] are the action labels' slots. Its fingerprint is the v1 one: actions and shapes are outside identity.
 */
internal fun notificationV2Fixture(
    day: LocalDate = LocalDate.of(2026, 10, 2), channel: String = "CHANNEL_SENTINEL",
    platform: String = "doordash", version: String = "8.1", hash: String = "0123456789abcdef",
    actions: List<TextSlot> = listOf(TextSlot("fedcba9876543210", "words:2"), TextSlot.WITHHELD),
    shapeField: NotifTextField? = NotifTextField.SUB_TEXT,
): JsonObject {
    val slots = NotifTextField.entries.associateWith {
        if (it == shapeField) TextSlot.ofShape(ShapeTemplate.MONEY) else TextSlot(hash, "words:1")
    }
    val item = NotificationSkeletonDto(SkeletonKind.NOTIFICATION, NotificationSkeletonSchema.SCHEMA_ID_V2, 1, 1,
        requireNotNull(CensusFingerprint.of(platform, channel, slots)), platform, platformAppVersion = version,
        engineVersion = 1, day = day.toString(), channelId = channel, slots = slots, actions = actions)
    return Json.parseToJsonElement(NotificationSkeletonSchema.serialize(item)).jsonObject
}

/** #78: a `uinode.skeleton.v2` screen with click flags and a shape slot beside a hashed one. */
internal fun screenV2Fixture(
    day: LocalDate = LocalDate.of(2026, 10, 2), platform: String = "doordash", version: String = "8.1",
    hash: String = "0123456789abcdef", shape: ShapeTemplate = ShapeTemplate.MONEY,
): JsonObject {
    val pay = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot.ofShape(shape)))
    val button = UiSkeletonNodeDto(
        className = "android.view.View", hasClickAction = true, text = mapOf("text" to TextSlot(hash, "words:1")),
    )
    val hidden = UiSkeletonNodeDto(className = "android.widget.TextView", isVisibleToUser = false)
    val root = UiSkeletonNodeDto(className = "android.widget.FrameLayout", isClickable = true, children = listOf(pay, button, hidden))
    val item = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), platform,
        platformAppVersion = version, engineVersion = 1, day = day.toString(), root = root)
    return Json.parseToJsonElement(SkeletonSchema.serialize(item)).jsonObject
}

/** #78: the same item as an older app sends it: the contract's own v1 projection, with [version] as its app version. */
internal fun asV1(v2: JsonObject, version: String = "8.0"): JsonObject {
    val text = when (val item = CensusSkeletonSchema.deserialize(v2.toString())) {
        is UiSkeletonDto -> SkeletonSchema.serialize(item.toV1().copy(platformAppVersion = version))
        is NotificationSkeletonDto -> NotificationSkeletonSchema.serialize(item.toV1().copy(platformAppVersion = version))
    }
    return Json.parseToJsonElement(text).jsonObject
}
