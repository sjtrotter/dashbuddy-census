package cloud.trotter.census.server

/**
 * What a field means (#81, ADR-0011 §15): a closed list a contributor picks from, never a value. Stored per slot as
 * epoch-tagged counts and shown to the operator.
 */
enum class SlotRole(val wire: String) {
    TOTAL_PAY("total_pay"),
    TIP("tip"),
    BASE_PAY("base_pay"),
    DEADLINE("deadline"),
    DISTANCE("distance"),
    ITEM_COUNT("item_count"),
    CUSTOMER_INFO("customer_info"),
    STORE_NAME("store_name"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(wire: String): SlotRole? = entries.firstOrNull { it.wire == wire }
    }
}

/** The note an "Ask the developer" item may carry (#81, gig.log#1627): a fixed list, never free text. */
enum class QuarantineNote(val wire: String) {
    MIGHT_CONTAIN_CUSTOMER("might_contain_customer"),
    MIGHT_CONTAIN_MY_INFO("might_contain_my_info"),
    UNSURE_WHAT_SCREEN("unsure_what_screen"),
    ;

    companion object {
        fun fromWire(wire: String): QuarantineNote? = entries.firstOrNull { it.wire == wire }
    }
}
