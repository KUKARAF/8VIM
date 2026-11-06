package inc.flide.vim8.ime.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import inc.flide.vim8.R

/** Height of the autofill strip; it replaces the word suggestions bar of the same height. */
val INLINE_BAR_HEIGHT = 40.dp

private const val MAX_SUGGESTIONS = 5
private const val CHIP_HEIGHT_DP = 32f
private const val MIN_CHIP_WIDTH_DP = 100f
private const val MIN_CHIP_HEIGHT_DP = 8f

object InlineAutofill {
    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Builds the request that tells the autofill service how its chips should look
     * and how large they may be. The chips are drawn by the autofill service, the
     * keyboard never sees their contents.
     */
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    fun buildRequest(
        context: Context,
        @ColorInt chipColor: Int,
        @ColorInt textColor: Int,
        @ColorInt subtitleColor: Int
    ): InlineSuggestionsRequest {
        val metrics = context.resources.displayMetrics
        val dp = { v: Float -> (v * metrics.density).toInt() }
        val chipBackground = Icon.createWithResource(context, R.drawable.inline_suggestion_chip)
            .setTint(chipColor)

        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(
                androidx.autofill.inline.common.ViewStyle.Builder()
                    .setBackground(chipBackground)
                    .setPadding(dp(8f), 0, dp(8f), 0)
                    .build()
            )
            .setSingleIconChipStyle(
                androidx.autofill.inline.common.ViewStyle.Builder()
                    .setBackground(chipBackground)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setTitleStyle(
                androidx.autofill.inline.common.TextViewStyle.Builder()
                    .setTextColor(textColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                androidx.autofill.inline.common.TextViewStyle.Builder()
                    .setTextColor(subtitleColor)
                    .setTextSize(12f)
                    .build()
            )
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()

        val spec = InlinePresentationSpec.Builder(
            Size(dp(MIN_CHIP_WIDTH_DP), dp(MIN_CHIP_HEIGHT_DP)),
            Size(metrics.widthPixels * 2 / 3, dp(CHIP_HEIGHT_DP + 8f))
        ).setStyle(styles).build()

        return InlineSuggestionsRequest.Builder(List(MAX_SUGGESTIONS) { spec })
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    /** Asks the system to render each suggestion; [onViews] gets the views that are ready. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun inflate(
        context: Context,
        response: InlineSuggestionsResponse,
        onViews: (List<View>) -> Unit
    ) {
        val suggestions: List<InlineSuggestion> = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            onViews(emptyList())
            return
        }
        val views = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        val size = Size(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (CHIP_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        )
        suggestions.forEachIndexed { index, suggestion ->
            runCatching {
                suggestion.inflate(context, size, context.mainExecutor) { view ->
                    views[index] = view
                    if (--pending == 0) onViews(views.filterNotNull())
                }
            }.onFailure {
                views[index] = null
                if (--pending == 0) onViews(views.filterNotNull())
            }
        }
    }
}

@Composable
fun InlineAutofillBar(views: List<View>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(INLINE_BAR_HEIGHT)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        views.forEach { view ->
            AndroidView(
                factory = {
                    // A view can only have one parent; detach it from any previous host.
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
