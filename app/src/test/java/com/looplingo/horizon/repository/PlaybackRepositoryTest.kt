package com.looplingo.horizon.repository

import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import com.looplingo.horizon.model.LoopMode
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.StartAction
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
 * Tests cover:
 *  - Loading a saved config from Room (happy path)
 *  - Returning null when no config exists for a video
 *  - Handling database errors gracefully (returns null)
 *  - Handling invalid loop mode string from DB (falls back to LOOP_INFINITE)
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
            startAction = 0,
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopMode = LoopMode.LOOP_INFINITE.name,  // Stored as enum name string
            loopCount = 1,
            autoAdvance = false
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.videoPath).isEqualTo("/video.mp4")
        assertThat(result.loopMode).isEqualTo(LoopMode.LOOP_INFINITE)
        assertThat(result.loopCount).isEqualTo(1)
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
    fun `getConfigForVideo handles invalid loop mode string gracefully`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            startAction = 0,
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopMode = "INVALID_MODE",  // Invalid enum name
            loopCount = 1,
            autoAdvance = false
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        // Should fall back to LOOP_INFINITE for invalid mode name
        assertThat(result!!.loopMode).isEqualTo(LoopMode.LOOP_INFINITE)
    }

    @Test
    fun `getConfigForVideo coerces negative loopCount to 1`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            startAction = 0,
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopMode = LoopMode.LOOP_X_TIMES.name,
            loopCount = -5,  // Invalid
            autoAdvance = false
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.loopCount).isEqualTo(1)
    }

    @Test
    fun `getConfigForVideo converts startAction to enum`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            startAction = 1,  // WAIT_MANUAL
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopMode = LoopMode.LOOP_INFINITE.name,
            loopCount = 1,
            autoAdvance = false
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        assertThat(result!!.startAction).isEqualTo(StartAction.WAIT_MANUAL)
    }

    @Test
    fun `getConfigForVideo handles out-of-range startAction via fromValue`() = runTest {
        val entity = PlaybackRuleEntity(
            videoPath = "/video.mp4",
            startAction = 99,  // Out of range — fromValue defaults to AUTO_PLAY
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopMode = LoopMode.LOOP_INFINITE.name,
            loopCount = 1,
            autoAdvance = false
        )
        coEvery { dao.getRuleForVideo("/video.mp4") } returns entity

        val result = repository.getConfigForVideo("/video.mp4")

        assertThat(result).isNotNull()
        // StartAction.fromValue(99) defaults to AUTO_PLAY
        assertThat(result!!.startAction).isEqualTo(StartAction.AUTO_PLAY)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SAVE CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `saveConfig persists valid config and returns true`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            loopMode = LoopMode.LOOP_X_TIMES,
            loopCount = 5
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
            loopMode = LoopMode.LOOP_X_TIMES,
            loopCount = 0  // Invalid — will be sanitized to 1
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
    fun `saveConfig converts StartAction enum to Int for entity`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            startAction = StartAction.WAIT_MANUAL,
            loopMode = LoopMode.LOOP_INFINITE,
            loopCount = 1
        )
        coEvery { dao.insertRule(any()) } returns Unit

        repository.saveConfig(config)

        coVerify {
            dao.insertRule(match { entity ->
                entity.startAction == 1  // WAIT_MANUAL.value
            })
        }
    }

    @Test
    fun `saveConfig returns false on database error`() = runTest {
        val config = PlaybackConfig(
            videoPath = "/video.mp4",
            loopMode = LoopMode.LOOP_INFINITE,
            loopCount = 1
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
}
