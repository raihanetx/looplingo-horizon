package com.looplingo.horizon.repository

import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import com.looplingo.horizon.model.PlaybackConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Unit tests for [PlaybackRepository].
 *
 * Tests cover the simplified A-B loop system:
 *  - Loading a saved config from Room (happy path)
 *  - Returning null when no config exists for a video
 *  - Handling database errors gracefully (returns null)
 *  - Coercing invalid speed values to valid range
 *  - Saving a valid config
 *  - Saving sanitizes invalid config before persisting
 *  - Handling database save errors (returns false)
 *  - Deleting a config
 */
class PlaybackRepositoryTest {

    private lateinit var dao: PlaybackRuleDao
    private lateinit var repository: PlaybackRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        repository = PlaybackRepository(dao)
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getConfigForVideo returns config when rule exists`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopCount = 1,
            speed = 1.0f
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.videoPath).isEqualTo("/video.mp4")
        assertThat(result.loopCount).isEqualTo(1)
        assertThat(result.speed).isEqualTo(1.0f)
    }

    @Test
    fun `getConfigForVideo returns null when no rule exists`() = runTest {
        coEvery { dao.getRuleForVideo("/nonexistent.mp4") } returns null

        val result = repository.getConfigForVideo("/nonexistent.mp4")

        assertThat(result).isNull()
    }

    @Test
    fun `getConfigForVideo returns null on database error`() = runTest {
        coEvery { dao.getRuleForVideo("/video.mp4") } throws IllegalStateException("DB error")

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNull()
    }

    @Test
    fun `getConfigForVideo coerces negative loopCount to 1`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopCount = -5,
            speed = 1.0f
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.loopCount).isEqualTo(1)
    }

    @Test
    fun `getConfigForVideo coerces speed below minimum to 0_25`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopCount = 1,
            speed = 0.1f  // Below minimum
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.speed).isEqualTo(0.25f)
    }

    @Test
    fun `getConfigForVideo coerces speed above maximum to 2_0`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopCount = 1,
            speed = 3.0f  // Above maximum
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.speed).isEqualTo(2.0f)
    }

    @Test
    fun `getConfigForVideo preserves valid speed values`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopCount = 1,
            speed = 0.75f
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.speed).isEqualTo(0.75f)
    }

    @Test
    fun `getConfigForVideo coerces negative rangeStartMs to 0`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            rangeStartMs = -100L,
            rangeEndMs = -1L,
            loopCount = 1,
            speed = 1.0f
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.rangeStartMs).isEqualTo(0L)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SAVE CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `saveConfig persists valid config and returns true`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            loopCount = 5,
            speed = 1.0f
        )
        coEvery { dao.insertRule(any()) } returns Unit

        val result = repository.saveConfig(config)

        assertThat(result).isTrue()
        coVerify { dao.insertRule(any()) }
    }

    @Test
    fun `saveConfig sanitizes invalid config before saving`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            loopCount = 0,  // Invalid — will be sanitized to 1
            speed = 1.0f
        )
        coEvery { dao.insertRule(any()) } returns Unit

        val result = repository.saveConfig(config)

        assertThat(result).isTrue()
        // Verify the entity saved has loopCount = 1 (sanitized)
        coVerify {
            dao.insertRule(match { entity ->
                entity.loopCount == 1
            })
        }
    }

    @Test
    fun `saveConfig preserves speed field in entity`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            speed = 0.5f
        )
        coEvery { dao.insertRule(any()) } returns Unit

        repository.saveConfig(config)

        coVerify {
            dao.insertRule(match { entity ->
                entity.speed == 0.5f
            })
        }
    }

    @Test
    fun `saveConfig returns false on database error`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            speed = 1.0f
        )
        coEvery { dao.insertRule(any()) } throws IllegalStateException("DB error")

        val result = repository.saveConfig(config)

        assertThat(result).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELETE CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `deleteConfigForVideo calls DAO and returns true`() = runTest {
        coEvery { dao.deleteRuleByVideoPath("/video.mp4") } returns Unit

        val result = repository.deleteConfigForVideo("/video.mp4")

        assertThat(result).isTrue()
        coVerify { dao.deleteRuleByVideoPath("/video.mp4") }
    }

    @Test
    fun `deleteConfigForVideo returns false on database error`() = runTest {
        coEvery { dao.deleteRuleByVideoPath("/video.mp4") } throws IllegalStateException("DB error")

        val result = repository.deleteConfigForVideo("/video.mp4")

        assertThat(result).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET ALL CONFIGURED MODES
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getAllConfiguredModes returns map of videoPath to displayBadge`() = runTest {
        val entities = listOf(
            PlaybackRuleEntity(videoPath = "/a.mp4", rangeStartMs = 0L, rangeEndMs = -1L, loopCount = 1, speed = 1.0f),
            PlaybackRuleEntity(videoPath = "/b.mp4", rangeStartMs = 5000L, rangeEndMs = 15000L, loopCount = 3, speed = 0.75f)
        )
        coEvery { dao.getAllRules() } returns entities

        val result = repository.getAllConfiguredModes()

        assertThat(result["/a.mp4"]).isEmpty()  // Normal playback → empty badge
        assertThat(result["/b.mp4"]).isEqualTo("AB")  // A-B loop → "AB"
    }

    @Test
    fun `getAllConfiguredModes returns empty map on error`() = runTest {
        coEvery { dao.getAllRules() } throws IllegalStateException("DB error")

        val result = repository.getAllConfiguredModes()

        assertThat(result).isEmpty()
    }
}
