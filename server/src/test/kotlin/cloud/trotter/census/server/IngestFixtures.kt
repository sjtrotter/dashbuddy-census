package cloud.trotter.census.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Hand-built CaptureEnvelopeDto / UiNodeDto wire fixture: ordinary chrome, three nodes. */
internal fun envelopeFixture(): JsonObject = Json.parseToJsonElement(
    """{
        "captureId":"12345678-1234-4234-8234-123456789abc",
        "pipelineId":"screen","schemaId":"uinode.v1","timestamp":1789689600000,"platform":"doordash",
        "ruleId":"doordash.screen.waiting_for_offer","classificationName":"WAITING_FOR_OFFER",
        "metadata":{"engineVersion":1,"rulesetFormatVersion":1,"rulesetReleaseTag":"v1",
            "rulesetSignature":"SIGNATURE_SENTINEL","pipelineVersions":{"screen":1},
            "stateMachineApiVersion":"1","appVersion":"1.0.0","platformAppVersion":"8.0.0",
            "deviceFingerprint":"DEVICE_SENTINEL"},
        "payload":{"class":"android.widget.LinearLayout","bounds":{"left":0,"top":0,"right":100,"bottom":100},
            "children":[
                {"class":"android.widget.TextView","text":"Looking for offers","bounds":{"left":0,"top":0,"right":100,"bottom":50}},
                {"class":"android.widget.Button","text":"Pause","isClickable":true,"bounds":{"left":0,"top":50,"right":100,"bottom":100}}
            ]},
        "windowContext":{"windowId":1,"windowType":1,"windowTitle":"Dasher","windowLayer":0,
            "isActive":true,"isFocused":true,"totalWindowCount":1}
    }""",
).jsonObject

internal fun healthFixture(): JsonObject = Json.parseToJsonElement(
    """{"day":"2026-09-18","platform":"doordash","platformAppVersion":"8.0.0","appVersion":"1.0.0",
        "rulesetVersion":"v1","admitted":812,"unknown":12,"trips":0,
        "ruleCounts":{"doordash.screen.waiting_for_offer":210}}""",
).jsonObject
