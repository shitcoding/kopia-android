package com.kopia.android.test

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import android.os.Build

/**
 * Base class for unit tests in the Kopia Android app.
 * Provides common setup for testing coroutines and LiveData.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE)
abstract class BaseUnitTest {
    
    // Executes each task synchronously using Architecture Components.
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    // Initializes mocks before each test method.
    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    // Test dispatcher for coroutines.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
}

