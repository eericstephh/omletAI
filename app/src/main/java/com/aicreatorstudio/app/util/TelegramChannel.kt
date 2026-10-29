package com.aicreatorstudio.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 2026-10-20 — the app's Telegram channel link.
 *
 * The home screen's header carries a Telegram button that opens the
 * channel. Two URLs are involved and the order matters:
 *
 *  1. [APP_URI] — the Telegram app's own scheme. When Telegram is
 *     installed this opens the channel **inside the app**, which is
 *     what the user expects from a Telegram button.
 *  2. [WEB_URL] — the universal `https://t.me/...` link. The Telegram
 *     app registers this host too, so it still lands in the app when
 *     the app is installed; otherwise the browser opens it.
 *
 * The web URL is therefore the **fallback that always works**, and
 * the app scheme is the better experience when it is available.
 *
 * ## Why the intent is launched without `resolveActivity`
 *
 * `resolveActivity` is subject to **package visibility** filtering on
 * Android 11+ — a query for a handler of a custom scheme only
 * returns results for apps the app has declared visibility for (via
 * `<queries>` or a matching `intent-filter` in the manifest). The
 * manifest declares neither, so `resolveActivity` would answer
 * "nothing handles this" even on a phone where Telegram IS
 * installed, and the app would always fall through to the browser.
 *
 * Launching directly and catching `ActivityNotFoundException` asks
 * the *system* the same question without the visibility filter, so
 * the Telegram app is used whenever it exists. Every failure mode is
 * caught: a device with neither Telegram nor a browser simply does
 * nothing instead of crashing the home screen.
 */
object TelegramChannel {

    /** The channel's universal web link — also the value to show. */
    const val WEB_URL: String = "https://t.me/AIerix"

    /** The channel's handle (the part after `t.me/`). */
    const val HANDLE: String = "AIerix"

    /** The Telegram app's private scheme for a channel by handle. */
    const val APP_URI: String = "tg://resolve?domain=$HANDLE"

    /**
     * Open the channel.
     *
     * @return true when a link was handed to the system, false when
     *   nothing on the device could handle it.
     */
    fun open(context: Context): Boolean {
        if (tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(APP_URI)))) return true
        return tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL)))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        // NEW_TASK: the context may be the application context (the
        // button is reachable from a Compose node that is not always
        // inside an Activity), and an external app is expected to
        // come to the front in its own task.
        val withFlags = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(withFlags)
            true
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device handles this URL.
            false
        } catch (_: SecurityException) {
            // A restricted profile can refuse the launch; the web
            // fallback is attempted next.
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
