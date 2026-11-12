package com.fujikon.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.render.ImageRenderer
import com.fujikon.app.render.PresetThumbnailStore
import com.fujikon.app.ui.components.PresetCard
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.42: 主预设列表的卡片预览必须使用传入的照片，而不是永远画内置样张。
 * 每个用例单次挂载（Robolectric 下重复 setContent 会破坏 composition），
 * 「重新选照片」的状态语义由 SelectionStoreTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PresetCardPreviewPhotoTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun photo(id: Long, name: String) = PhotoItem(
        id = id,
        uri = Uri.parse("file:///fujikon_missing_photos/$name"),
        displayName = name,
        dateTaken = id,
        dateAdded = id,
        bucketId = "camera",
        bucketName = "Camera",
        width = 1200,
        height = 800,
        mimeType = "image/jpeg",
        size = 1024L
    )

    private fun showCard(photo: PhotoItem?) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = PresetThumbnailStore(context, ImageRenderer())
        composeRule.setContent {
            FujiKonTheme {
                PresetCard(
                    preset = Preset.none(),
                    photo = photo,
                    thumbnailStore = store,
                    onClick = {}
                )
            }
        }
    }

    @Test
    fun cardPreviewUsesTheFirstSelectedPhoto() {
        showCard(photo(1L, "FIRST_PICK.jpg"))
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasContentDescription("FIRST_PICK.jpg", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cardPreviewUsesTheNewlySelectedPhoto() {
        // 重新选择照片后 PresetsScreen 传入新的 previewPhoto，卡片应基于它渲染。
        showCard(photo(2L, "SECOND_PICK.jpg"))
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasContentDescription("SECOND_PICK.jpg", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cardFallsBackToSampleSceneWhenNoPhotoWasPicked() {
        showCard(null)
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasContentDescription("内置样张", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
