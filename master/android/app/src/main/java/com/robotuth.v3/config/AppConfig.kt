package com.robotuth.v3.config

import com.robotuth.v3.BuildConfig

/**
 * Application configuration constants
 */
object AppConfig {
    /** Provisioned at build time; never commit it to source control. */
    val ROBOT_API_TOKEN: String = BuildConfig.ROBOT_API_TOKEN
    // LAN gateway (nginx on :10010) — the robot and phones reach the server over Wi-Fi.
    const val DEFAULT_API_BASE_URL = "http://172.16.1.15:10010/"
    // Old addresses persisted by earlier installs; rewritten once on upgrade (see
    // SettingsRepositoryImpl). Kept so existing installs migrate to the LAN gateway.
    const val LEGACY_TAILSCALE_API_BASE_URL = "http://100.91.218.74:8001/"
    const val LEGACY_FUNNEL_API_BASE_URL = "https://uthuth.tail713760.ts.net/"

    // Default CTC STT server URL (same API host)
    const val DEFAULT_CTC_SERVER_URL = DEFAULT_API_BASE_URL

    // Hidden config (HomeScreen: tap greeting 7 times)
    const val CONFIG_TAP_COUNT = 7
    const val CONFIG_PASSWORD = "adminuth@314"

    // UTH campus VR map (Pano2VR @ vr.uth.edu.vn — requires network + WebGL)
    const val VR_CAMPUS_URL = "https://vr.uth.edu.vn/"

    // Audio recording configuration (Parakeet CTC — see ParakeetSttService)
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHANNEL_CONFIG = "CHANNEL_IN_MONO"
    const val AUDIO_FORMAT = "ENCODING_PCM_16BIT"
    /** Legacy chunk length; default path is whole-utterance push-to-talk. */
    const val AUDIO_CHUNK_DURATION_MS = 2500L
    /** Max whole utterance buffered on device before auto-cap. */
    const val AUDIO_MAX_UTTERANCE_MS = 20_000L
    const val AUDIO_BYTES_PER_SAMPLE = 2

    // Orion visual ability — face focus-follow (see com.robotuth.v3.vision.FocusFollowController)
    /**
     * Kill switch. Keep OFF on the remote-control kiosk: focus-follow asks CoreService's
     * navigation planner to take the chassis on every Activity start. On an unlocalized robot
     * parked beside its charging pile that puts DynamicStoppingPolicy into `in dangerous` and
     * activates the hardware obstacle buzzer continuously. It also competes with remote drive
     * for the same chassis. Re-enable only for a dedicated, localized face-follow deployment.
     */
    const val FOCUS_FOLLOW_ENABLED = false
    /** ms Orion waits without a lock before it reports STATUS_GUEST_LOST. */
    const val FOCUS_FOLLOW_LOST_TIMEOUT_MS = 5_000L
    /** metres; past this Orion reports STATUS_GUEST_FARAWAY (keep < room depth). */
    const val FOCUS_FOLLOW_MAX_DISTANCE_M = 3.0f
    /** Back-off before re-arming after LOST / STOP_FOLLOW (vendor says stop, then start again). */
    const val FOCUS_FOLLOW_REARM_DELAY_MS = 1_500L
    /**
     * Navigation speed grade applied while focus-follow drives the chassis, so the body
     * turns toward a face more gently. `startFocusFollow` has no speed argument of its own
     * and the SG04 head-PTZ is faulted (it must rotate the whole body), so the only SDK
     * lever is `RobotApi.setLineSpeed`. Orion firmware vocabulary: "slow" | "standard" |
     * "fast". Set to "" to leave the robot's own speed untouched. The previous grade is read
     * with `getLineSpeed()` and restored when the app leaves the foreground.
     */
    const val FOCUS_FOLLOW_NAVI_SPEED = "slow"

    // Orion navigation health — obstacle-alert latch diagnostics
    // (see com.robotuth.v3.vision.FocusFollowController.sweepNaviHealth)
    /**
     * Read-only sweep run every time CoreService connects: dumps the persisted vendor
     * obstacle-avoid settings, the nav config and whether the nav core still thinks it is
     * navigating. Needed because the continuous obstacle buzzer on OS-R-SG04 turned out to be
     * a *stuck* `DynamicStoppingPolicy` state, not an audio cue: the only thing that cleared it
     * was restarting `com.ainirobot.navigationservice`, which the vendor watchdog then restarts
     * again — so the sweep has to re-run on every connection to be watchdog-proof.
     */
    const val NAVI_HEALTH_PROBE_ENABLED = true
    /** ms to wait after CoreService connects before probing, so the nav module is up. */
    const val NAVI_HEALTH_PROBE_DELAY_MS = 2_500L
    /**
     * Also *fix* what the sweep finds: when the nav core reports it is still navigating while
     * this kiosk app never asked for navigation, clear it with `stopNavigation` (the same call
     * the stock stop button makes) instead of only logging it. Set to false for a strictly
     * read-only sweep.
     */
    const val NAVI_HEALTH_CLEAR_STALE_GOAL = true

    // ── Streaming ASR (relay /ws/asr/{session} — server/api/asr_stream.py) ──
    /**
     * true = mic conversation stream frame PCM qua WS; server tự cắt câu theo
     * im lặng và decode **từng đoạn bằng đúng model batch đang chạy** (Parakeet)
     * → trải nghiệm chữ-chạy-dần mà không đổi chất lượng nhận dạng.
     * Kết nối fail / timeout giữa câu → tự rơi về đường POST whole-utterance
     * trên buffer đã giữ — robot không bao giờ đứng chờ vì hạ tầng stream.
     * Bật qua Gradle property `ROBOT_STREAMING_ASR=true` (default false) để một
     * build test không đòi sửa source; giá trị bake vào BuildConfig.
     */
    val STREAMING_ASR_ENABLED: Boolean = BuildConfig.ROBOT_STREAMING_ASR

    // ── Wake word ("hello") — app-side gate trên luồng streaming ──
    /**
     * true = ở Idle, conversation mở mic nền + stream liên tục (relay
     * turn_end=auto); chỉ lượt nào khớp [WAKE_WORD_PATTERNS] ở **đầu câu** mới
     * vào hội thoại — tiếng ồn nền và "chào" xã giao bị loại client-side.
     * Monitor DỪNG khi robot Thinking/Speaking (không tự đánh thức bằng TTS
     * của chính mình); re-arm khi về Idle. Cần [STREAMING_ASR_ENABLED] = true.
     * Bật qua Gradle property `ROBOT_WAKE_WORD=true` (default false).
     */
    val WAKE_WORD_ENABLED: Boolean = BuildConfig.ROBOT_WAKE_WORD
    /** Chuỗi hiển thị cho UI. */
    const val WAKE_WORD_LABEL = "hello"
    /**
     * Mẫu đã chuẩn hóa-hoà (bỏ dấu), token cách 1 dấu cách, khớp theo từ nguyên
     * ở tối đa 2 từ đầu câu. CÓ CHỦ ĐÍCH không gồm "chao"/"xin chào": hành lang
     * đầy người chào nhau. ASR tiếng Việt biến thể hoá "hello" → phải liệt kê
     * dạng không dấu: helo/halo/a lo/he lo…
     */
    const val WAKE_WORD_PATTERNS =
        "hello robot,helo robot,hello,helo,halo,he lo,ha lo,alo,a lo,hi lo,robot,ro bot"
    /** Sau "hello" trần, cửa sổ nhận câu hỏi kế tiếp không cần wake lại. */
    const val WAKE_FOLLOWUP_WINDOW_MS = 8_000L

    // ── Robot link (remote control; see server/api/robot_link.py + remote-control spec) ──
    /**
     * Master kill switch. Enabled for the supervised on-robot validation session
     * (2026-09): the link itself must run before WS durability under Orion's app
     * suspension can be observed. First real drive: low speed, operator at the
     * kill switch. Flip back to false for unattended/production installs.
     */
    const val ROBOT_LINK_ENABLED = true
    /**
     * LAN endpoint — the robot dials the server directly over Wi-Fi; the nginx gateway
     * (port 10010) proxies the /ws paths to the app server.
     */
    const val ROBOT_LINK_WS_URL = "ws://172.16.1.15:10010/ws"
    const val ROBOT_LINK_HEARTBEAT_S = 10L
    /** Dead-man watchdog: manual motion stops locally without a refresh inside this window. */
    const val ROBOT_LINK_WATCHDOG_MS = 500L
    /**
     * Speed envelope the robot actually supports — mirrors the Orion SDK's authoritative
     * constants `NavigationAdvancedBean.{LINEAR,ANGULAR}_SPEED_{MIN,MAX}` (verified against
     * `com.orionstar.robot:core:12.2.1`): linear 0.1–1.2 m/s, angular 0.2–1.8 rad/s. The SDK
     * rectifies (clamps) any value outside these, so advertising them lets the operator console
     * size its slider to the real capability instead of a hardcoded guess. Manual `move` maps the
     * linear slider proportionally onto the angular range for turns (see OrionMotion.move).
     */
    const val ROBOT_LINK_MIN_LINEAR = 0.1f
    const val ROBOT_LINK_MAX_LINEAR = 1.2f
    const val ROBOT_LINK_MIN_ANGULAR = 0.2f
    const val ROBOT_LINK_MAX_ANGULAR = 1.8f
    /** Conservative default the console starts on — the operator must deliberately push toward max. */
    const val ROBOT_LINK_DEFAULT_LINEAR = 0.3f
    const val ROBOT_LINK_BACKOFF_MAX_S = 30L
    /** Language tag baked into new maps by startCreatingMap (matches vendor map). */
    const val MAP_LANGUAGE = "vi_VN"
}
