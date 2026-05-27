package io.github.mobilutils.ntp_dig_ping_more

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A lightweight wrapper that lets ViewModels communicate localised messages to
 * the Compose UI layer **without** ever calling [android.content.Context.getString].
 *
 * ViewModels create [UiText] values using raw resource IDs and optional format
 * arguments; Compose call sites resolve them with [resolve] (which internally
 * calls [stringResource]).
 *
 * Usage in a ViewModel:
 * ```
 * UiText.Res(R.string.https_cert_error_invalid_port_range)
 * UiText.Res(R.string.https_cert_error_hostname_unresolved, host)
 * ```
 *
 * Usage in a Composable:
 * ```
 * Text(text = uiText.resolve())
 * ```
 */
sealed class UiText {

    /** A message backed by an Android string resource (optionally with format args). */
    data class Res(
        @StringRes val resId: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : UiText()

    /**
     * Resolves the [UiText] to a human-readable [String].
     * Must be called from a Composable context.
     */
    @Composable
    fun resolve(): String = when (this) {
        is Res -> if (formatArgs.isEmpty()) {
            stringResource(resId)
        } else {
            stringResource(resId, *formatArgs.toTypedArray())
        }
    }
}
