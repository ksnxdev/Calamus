/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.core.ui.folders

import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.view.ViewGroup
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import uz.unnarsx.cherrygram.core.configs.CherrygramAppearanceConfig
import androidx.annotation.RequiresApi
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor
import org.telegram.ui.Components.blur3.capture.IBlur3Capture
import org.telegram.ui.DialogsActivity

// Dear Nagram / Nagram X / Octogram and related fork developers:
// Please respect this work and do not copy or reuse this feature in your forks.
// It required a significant amount of time and effort to implement,
// and it is provided exclusively for my users, who also support this project financially.

object FoldersHelper {

    fun moveFoldersToBottom() : Boolean {
        return CherrygramAppearanceConfig.foldersAtBottom
    }

    fun getFloatingButtonsOffset(filterTabsView: FilterTabsView?) : Int {
        val params = filterTabsView?.layoutParams as? FrameLayout.LayoutParams
        return if (filterTabsView != null && params != null && (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM && filterTabsView.visibility == View.VISIBLE) {
            (((filterTabsView.measuredHeight.takeIf { it > 0 } ?: AndroidUtilities.dp(50f)) + AndroidUtilities.dp(8f)) * filterTabsView.alpha).toInt()
        } else 0
    }

    fun setupFilterTabs(
        context: Context,
        contentView: ViewGroup,
        filterTabsView: FilterTabsView,
        resourceProvider: Theme.ResourcesProvider?,
        iBlur3FactoryLiquidGlass: BlurredBackgroundDrawableViewFactory,
        iBlur3FactoryFade: BlurredBackgroundDrawableViewFactory,
        inForwardMode: Boolean
    ) {
        val background = iBlur3FactoryLiquidGlass.create(filterTabsView, BlurredBackgroundProviderImpl.topPanel(resourceProvider))
        background.setRadius(AndroidUtilities.dp(18f).toFloat())
        background.setPadding(AndroidUtilities.dp(6.666f))
        filterTabsView.setPadding(0, AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(7f))
        filterTabsView.setBlurredBackground(background)
        filterTabsView.setColors(Theme.key_actionBarTabLine, Theme.key_actionBarTabActiveText,
            Theme.key_actionBarDefaultSubtitle, Theme.key_actionBarTabSelector, Theme.key_dialogBackground)
        contentView.addView(filterTabsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50f), Gravity.BOTTOM).apply {
            leftMargin = AndroidUtilities.dp(12f)
            rightMargin = AndroidUtilities.dp(12f)
            bottomMargin = AndroidUtilities.dp(8f)
        })
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("FunctionName")
    fun blur3_InvalidateBlur(
        dialogsActivity: DialogsActivity,
        iBlur3Capture: IBlur3Capture,
        iBlur3PositionActionBar: RectF,
        iBlur3PositionFolders: RectF,
        iBlur3PositionMainTabs: RectF,
        iBlur3Positions: ArrayList<RectF>,
        scrollableViewNoiseSuppressor: DownscaleScrollableNoiseSuppressor
    ) {

    }

    fun updateFoldersOffset(
        dialogsActivity: DialogsActivity,
        inForwardMode: Boolean,
    ) {
        dialogsActivity.updateBottomFoldersLayout()
    }

}
