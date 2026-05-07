package com.looplingo.horizon.repository

import android.content.Context
import com.looplingo.horizon.data.dao.VideoDao
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.util.FileScanner
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VideoRepository].
 *
 * Tests cover:
 *  - Getting videos from the Flow
 *  - Refreshing videos from MediaStore via FileScanner
 *  - Syncing new videos into the Room cache
 *  - Removing stale videos from the cache
 *  - Looking up content URIs by path
 *  - Error handling (scanner failure, DB failure)
 */
class VideoRepositoryTest {

    private lateinit var dao: VideoDao
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var fileScanner: FileScanner
    private lateinit var context: Context
    private lateinit var repository: VideoRepository

    private val testVideo = VideoEntity(
        path = "/storage/emulated/0/DCIM/test.mp4",
        title = "Test Video",
        duration = 60000L,
        size = 1024000L,
        lastModified = 1700000000000L,
        contentUri = "content://media/external/video/media/123"
    )

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        fileScanner = mockk(relaxed = true)
        context = mockk(relaxed = true)
        repository = VideoRepository(dao, playbackRepository, fileScanner, context)
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET VIDEOS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getVideos returns Flow from DAO`() = runTest {
        val expectedVideos = listOf(testVideo)
        every { dao.getAllVideosFlow() } returns flowOf(expectedVideos)

        val result = repository.getVideos().first()

        assertThat(result).hasSize(1)
        assertThat(result[0].path).isEqualTo(testVideo.path)
    }

    @Test
    fun `getVideos returns empty list when no videos cached`() = runTest {
        every { dao.getAllVideosFlow() } returns flowOf(emptyList())

        val result = repository.getVideos().first()

        assertThat(result).isEmpty()
    }

    // ══════════════════════════════════════════════════════════════════════
    // REFRESH VIDEOS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `refreshVideos inserts new videos into cache`() = runTest {
        val scannedVideos = listOf(testVideo)
        coEvery { fileScanner.scanVideosList(context) } returns scannedVideos
        coEvery { dao.getAllVideos() } returns emptyList()

        repository.refreshVideos()

        coVerify { dao.insertAll(scannedVideos) }
    }

    @Test
    fun `refreshVideos removes stale videos from cache`() = runTest {
        val staleVideo = testVideo.copy(path = "/storage/emulated/0/DCIM/deleted.mp4")
        coEvery { fileScanner.scanVideosList(context) } returns emptyList()
        coEvery { dao.getAllVideos() } returns listOf(staleVideo)

        repository.refreshVideos()

        coVerify { dao.deleteByPaths(listOf("/storage/emulated/0/DCIM/deleted.mp4")) }
    }

    @Test
    fun `refreshVideos does not insert if all videos already cached`() = runTest {
        coEvery { fileScanner.scanVideosList(context) } returns listOf(testVideo)
        coEvery { dao.getAllVideos() } returns listOf(testVideo)

        repository.refreshVideos()

        // insertAll should not be called with new videos (but may be called for updates)
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `refreshVideos handles scanner SecurityException gracefully`() = runTest {
        coEvery { fileScanner.scanVideosList(context) } throws SecurityException("No permission")

        // Should not throw
        repository.refreshVideos()
    }

    @Test
    fun `refreshVideos handles scanner generic exception gracefully`() = runTest {
        coEvery { fileScanner.scanVideosList(context) } throws RuntimeException("Unexpected error")

        // Should not throw
        repository.refreshVideos()
    }

    @Test
    fun `refreshVideos handles DAO error during syncCache`() = runTest {
        coEvery { fileScanner.scanVideosList(context) } returns listOf(testVideo)
        coEvery { dao.getAllVideos() } throws IllegalStateException("DB error")

        // Should not throw — the error is caught in syncCache
        repository.refreshVideos()
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET CONTENT URI
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getContentUriForPath returns URI when video exists`() = runTest {
        coEvery { dao.getContentUriForPath(testVideo.path) } returns "content://media/external/video/media/123"

        val result = repository.getContentUriForPath(testVideo.path)

        assertThat(result).isEqualTo("content://media/external/video/media/123")
    }

    @Test
    fun `getContentUriForPath returns null when video not found`() = runTest {
        coEvery { dao.getContentUriForPath("/nonexistent.mp4") } returns null

        val result = repository.getContentUriForPath("/nonexistent.mp4")

        assertThat(result).isNull()
    }

    @Test
    fun `getContentUriForPath returns null when contentUri is empty`() = runTest {
        coEvery { dao.getContentUriForPath("/video.mp4") } returns ""

        val result = repository.getContentUriForPath("/video.mp4")

        assertThat(result).isNull()
    }

    @Test
    fun `getContentUriForPath returns null on database error`() = runTest {
        coEvery { dao.getContentUriForPath("/video.mp4") } throws IllegalStateException("DB error")

        val result = repository.getContentUriForPath("/video.mp4")

        assertThat(result).isNull()
    }
}
