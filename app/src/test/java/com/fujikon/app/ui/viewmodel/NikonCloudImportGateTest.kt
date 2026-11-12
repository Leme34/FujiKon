package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.nic.CloudImportSupport
import com.fujikon.app.nic.CloudPictureControl
import com.fujikon.app.nic.NikonCloudDetail
import com.fujikon.app.nic.NikonCloudRecipe
import com.fujikon.app.nic.NikonCloudService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在线云创导入入口的防御性拦截：没有附件官方真实 NP3 数据的 recipe 绝不能
 * 请求详情、更不能写入用户预设列表。这是「假滤镜」的最后一道防线。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NikonCloudImportGateTest {

    private val supportedId = "01JSBM30XWQ6GG4XK0KSZ3FQEK" // Vitality Film（真实 NP3）
    private val fittedId = "01K7JQEDMX051ZFVR4GW77D1DW"     // Catgrain（仅拟合假 LUT）
    private val unknownId = "RECIPE-FAKE-1"                  // 在线目录里的普通方案

    private val catalog = listOf(
        NikonCloudRecipe(id = supportedId, name = "Vitality Film by Peng Mango"),
        NikonCloudRecipe(id = fittedId, name = "Catgrain"),
        NikonCloudRecipe(id = unknownId, name = "Brand New Recipe")
    )

    private val detail = NikonCloudDetail(
        brief = "测试说明",
        note = "创作者的说明\n测试说明",
        control = CloudPictureControl(baseName = "FLEXIBLE COLOR", saturation = -5)
    )

    @Volatile
    private var detailCalls = 0

    private inner class FakeService : NikonCloudService(baseUrl = "http://127.0.0.1:1") {
        override suspend fun fetchCatalog(): List<NikonCloudRecipe> = catalog
        override suspend fun fetchDetail(recipeId: String): NikonCloudDetail {
            detailCalls++
            return detail
        }
    }

    private lateinit var context: Context
    private lateinit var repository: PresetRepository
    private lateinit var vm: NikonCloudViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        repository = PresetRepository(context)
        detailCalls = 0
        vm = NikonCloudViewModel(FakeService(), repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.filesDir.deleteRecursively()
    }

    @Test
    fun fittedRecipeIsRejectedWithoutTouchingRepository() {
        vm.importOne(catalog.first { it.id == fittedId })

        assertEquals(
            NikonCloudViewModel.ItemState.Unsupported,
            vm.state.value.statuses[fittedId]
        )
        assertEquals(CloudImportSupport.UNSUPPORTED_HINT, vm.state.value.notice)
        assertEquals("不应为假滤镜请求详情", 0, detailCalls)
        assertTrue("不应触发 repository.load()", repository.presets.value.isEmpty())
        assertTrue(
            "假滤镜绝不能写入预设列表",
            repository.presets.value.none { it.contentHash == "nic:$fittedId" }
        )
    }

    @Test
    fun unknownCatalogRecipeIsRejectedByDefault() {
        vm.importOne(catalog.first { it.id == unknownId })

        assertEquals(
            NikonCloudViewModel.ItemState.Unsupported,
            vm.state.value.statuses[unknownId]
        )
        assertEquals(0, detailCalls)
        assertTrue(repository.presets.value.isEmpty())
        assertTrue(repository.presets.value.none { it.contentHash == "nic:$unknownId" })
    }

    @Test
    fun viewModelExposesTheSameSupportDecisionAsUi() {
        assertTrue(vm.isImportSupported(supportedId))
        assertFalse(vm.isImportSupported(fittedId))
        assertFalse(vm.isImportSupported(unknownId))
        assertFalse(vm.isImportSupported(""))
        assertEquals(CloudImportSupport.supportedIds.size, 6)
    }
}
