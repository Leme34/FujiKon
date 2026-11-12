package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.nic.CloudPictureControl
import com.fujikon.app.nic.NikonCloudDetail
import com.fujikon.app.nic.NikonCloudRecipe
import com.fujikon.app.nic.NikonCloudService
import com.fujikon.app.nic.OfficialCloudPresets
import com.fujikon.app.nic.contentHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 端到端验证 1.6.47 的核心诉求：预置与在线导入共用同一 contentHash 后，
 * 用户再导入同名官方云创滤镜会被判 Duplicate，列表里不会出现两个滤镜。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NikonCloudImportDedupTest {

    private lateinit var context: Context

    private val detail = NikonCloudDetail(
        brief = "官方样张说明",
        note = "简化参数（不含曲线）",
        control = CloudPictureControl()
    )

    private val recipes = listOf(
        NikonCloudRecipe(id = "01JSBM30XWQ6GG4XK0KSZ3FQEK", name = "Vitality Film by Peng Mango"),
        NikonCloudRecipe(id = "01JSBM2ZVF6SX4VSEK76K1VGJQ", name = "Filmic by Fabio Oliveira"),
        NikonCloudRecipe(id = "01JAWAKHJ0DG7KCBY6BNAW4271", name = "Nature by Links")
    )

    /** 不发网络请求的可控 service。 */
    private inner class FakeService : NikonCloudService() {
        var detailCalls = 0
        override suspend fun fetchCatalog(): List<NikonCloudRecipe> = recipes
        override suspend fun fetchDetail(recipeId: String): NikonCloudDetail {
            detailCalls++
            return detail
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        OfficialCloudPresets.clearCache()
    }

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
        OfficialCloudPresets.clearCache()
        Dispatchers.resetMain()
    }

    /** 等待 importOne 的 viewModelScope 协程离开 Importing 态。 */
    private fun awaitSettled(vm: NikonCloudViewModel, id: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val status = vm.state.value.statuses[id]
            if (status != null && status != NikonCloudViewModel.ItemState.Importing) return
            Thread.sleep(10)
        }
    }

    @Test
    fun importingAnOfficialRecipeAlreadyPresetIsDuplicateAndAddsNothing() {
        val repository = PresetRepository(context)
        runBlocking { repository.load() }
        val service = FakeService()
        val vm = NikonCloudViewModel(service, repository)

        val before = repository.presets.value.size
        val recipe = recipes.first() // Vitality Film by Peng Mango

        vm.importOne(recipe)
        awaitSettled(vm, recipe.id)

        val state = vm.state.value
        assertEquals(
            "已被预置的官方滤镜应判为 Duplicate",
            NikonCloudViewModel.ItemState.Duplicate,
            state.statuses[recipe.id]
        )
        assertEquals("导入不得新增第二个滤镜", before, repository.presets.value.size)
        assertEquals(
            1,
            repository.presets.value.count { it.contentHash == recipe.contentHash() }
        )
    }

    @Test
    fun everyOfficialPresetIsProtectedFromDuplicateImport() {
        val repository = PresetRepository(context)
        runBlocking { repository.load() }
        val service = FakeService()

        recipes.forEach { recipe ->
            val vm = NikonCloudViewModel(service, repository)
            val before = repository.presets.value.size
            vm.importOne(recipe)
            awaitSettled(vm, recipe.id)
            assertEquals(
                "${recipe.name} 应判为 Duplicate",
                NikonCloudViewModel.ItemState.Duplicate,
                vm.state.value.statuses[recipe.id]
            )
            assertEquals(before, repository.presets.value.size)
        }
        assertTrue("三个官方方案都应在预置里各有一份",
            recipes.all { r ->
                repository.presets.value.count { p ->
                    p.contentHash == r.contentHash()
                } == 1
            })
    }

    @Test
    fun importingAnUnmappedOfficialRecipeStillWorks() {
        val repository = PresetRepository(context)
        runBlocking { repository.load() }
        val service = FakeService()
        val vm = NikonCloudViewModel(service, repository)

        // 官方目录里存在、但附件没有对应 NP3 的方案：仍可正常导入一份。
        val unmapped = NikonCloudRecipe(
            id = "01M4SE2383N04DY8N1WB96N336", name = "Mate Film by Choi matae"
        )
        val before = repository.presets.value.size
        vm.importOne(unmapped)
        awaitSettled(vm, unmapped.id)

        assertEquals(
            NikonCloudViewModel.ItemState.Imported,
            vm.state.value.statuses[unmapped.id]
        )
        assertEquals(before + 1, repository.presets.value.size)
        assertTrue(repository.presets.value.any { it.name == unmapped.name })
    }
}
