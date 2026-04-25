package com.safeword.android.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.safeword.android.transcription.TranscriptionCoordinator
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Macrobenchmark for transcription performance.
 * Measures critical paths in the STT pipeline to ensure performance targets are met.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TranscriptionBenchmark {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val benchmarkRule = BenchmarkRule()

    @Inject
    lateinit var transcriptionCoordinator: TranscriptionCoordinator

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun benchmarkTranscriptionStart() {
        benchmarkRule.measureRepeated {
            transcriptionCoordinator.startStreamingRecording()
        }
    }

    @Test
    fun benchmarkTranscriptionStop() {
        benchmarkRule.measureRepeated {
            transcriptionCoordinator.stopStreamingRecording()
        }
    }

    @Test
    fun benchmarkStateUpdate() {
        benchmarkRule.measureRepeated {
            transcriptionCoordinator.state.value
        }
    }
}
