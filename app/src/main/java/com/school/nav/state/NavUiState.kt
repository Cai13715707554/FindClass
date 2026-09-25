package com.school.nav.state

import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.Floor
import com.school.nav.core.model.LngLat
import com.school.nav.core.navigation.RouteResult
import com.school.nav.core.model.Target
import com.school.nav.location.LocationAvailability

/** 导航文案状态，UI 据此决定展示加载中、空态还是指令。 */
sealed interface RouteUiState {

    /** 位置还没确定，无法计算导航。 */
    data object Loading : RouteUiState

    /** 还没选目标。 */
    data object Empty : RouteUiState

    /** 目标就是当前位置。 */
    data class Arrived(val target: Target) : RouteUiState

    /** 正常导航结果。 */
    data class Ready(val route: RouteResult) : RouteUiState

    /** 数据不足，给出原因。 */
    data class Unavailable(val reason: String) : RouteUiState
}

/** 楼层估算在 UI 上的呈现。 */
sealed interface FloorUiState {
    /** 正在读取气压计。 */
    data object Stabilizing : FloorUiState

    /** 已判断出楼层。 */
    data class Known(val floor: Floor) : FloorUiState

    /** 设备没有气压计。 */
    data object SensorMissing : FloorUiState

    /** 气压不可信。 */
    data object Unreliable : FloorUiState
}

/** 当前定位来源，用于位置卡片上的说明文字。 */
enum class CurrentSource {
    /** 还没定位。 */
    Unknown,

    /** 系统/高德定位给出，气压计判断了楼层。 */
    Auto,

    /** 用户手动指定。 */
    Manual,

    /** 室内信号弱，沿用上次锁定的楼栋。 */
    LastLocked,
}

/** 首页完整状态。 */
data class NavUiState(
    val isLocating: Boolean = true,
    val building: Building? = null,
    /** 楼层未知时为 null —— 此时不猜，提示用户手动选择。 */
    val floor: Floor? = null,
    val element: Element? = null,
    val floorState: FloorUiState = FloorUiState.Stabilizing,
    val source: CurrentSource = CurrentSource.Unknown,
    val locationChannel: String = "",
    val target: Target? = null,
    val route: RouteUiState = RouteUiState.Loading,
    /** 是否可以展示“我知道了”之类的手动兜底提示。 */
    val needsManualFloor: Boolean = false,
    /**
     * 定位当前不可用的原因；null 表示定位正常或尚未判定。
     *
     * 这是「定位中…」不会永久卡住的保证：一旦这个字段非 null，
     * [isLocating] 必定为 false，位置卡片会改为提示用户手动选择位置。
     */
    val locationUnavailable: LocationAvailability? = null,

    /**
     * 最近一次 GPS 原始经纬度（GCJ-02）。
     *
     * 与 [element] 不同：这是**传感器原始值**，不做任何业务解读。
     * “我的 → 定位测试”页面直接展示它，用于现场核对取点是否正常。
     */
    val positionPoint: LngLat? = null,

    /** 最近定位点的水平精度（米）。用来判断这个点可不可信，越小越好。 */
    val positionAccuracyMeters: Double? = null,

    /** 最近定位点的来源 provider，例如 `gps` / `network` / `amap`。 */
    val positionProvider: String? = null,

    /** 最近一次收到定位点的时刻（毫秒时间戳），用于显示“实时更新”的证据。 */
    val positionUpdatedAtMillis: Long = 0L,

    /**
     * 气压计平滑后的原始读数（hPa）。
     *
     * 楼层判断用的是「相对高度」，而相对高度依赖用户校准的基准；
     * 这里保留原始气压，便于排查“到底有没有读到传感器”和“基准校得对不对”。
     */
    val pressureHpa: Double? = null,

    /**
     * 相对校准基准的推算高度（米）。未校准或样本不足时为 null。
     *
     * 这是“定位测试”里的「高度」数值：它与楼层判断用的是同一个量，
     * 但直接展示原始数字，方便核对某次判层是不是被气压异常带偏了。
     */
    val estimatedHeightM: Double? = null,

    /** 本机是否有气压计。 */
    val barometerAvailable: Boolean = true,
) {
    /**
     * 展示用的高度（米）——**没有气压计也一定有值**。
     *
     * 优先用气压计推算的相对高度；没有气压计（或还没校准）时，
     * 退回当前楼层的 `relative_height_m`（该层相对地面层的高度）。
     *
     * 这样“我的 → 定位测试”里高度永远有数字可看：气压计给的是连续的相对高度，
     * 楼层给的是离散步进值，两者含义略有差别，因此调用方需要同时看
     * [heightSourceText] 来知道这个数字是从哪来的。
     *
     * 楼层也没确定时返回 null（此时确实无从得知高度）。
     */
    val displayHeightM: Double?
        get() = estimatedHeightM ?: floor?.relativeHeightM

    /** [displayHeightM] 的来源说明，避免用户把“楼层高度”误读成“实测高度”。 */
    val heightSourceText: String?
        get() = when {
            estimatedHeightM != null -> "气压计实测（相对校准基准）"
            floor != null -> "按楼层估算（该层相对地面高度）"
            else -> null
        }
    /** `A栋 · 3楼 · 语文教研室`；信息不全时降级展示。 */
    val positionText: String
        get() {
            val b = building ?: return "尚未定位"
            val f = floor ?: return "${b.name} · 楼层待确认"
            val e = element ?: return "${b.name} · ${f.displayName}"
            return "${b.name} · ${f.displayName} · ${e.name}"
        }

    /** 位置是否已经三级齐全，可以导航。 */
    val isPositionComplete: Boolean get() = building != null && floor != null && element != null
}

/** 一次性提示消息。 */
data class ToastMessage(
    val id: Long,
    val text: String,
)
