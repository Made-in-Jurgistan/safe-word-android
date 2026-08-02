package com.safeword.android.ui

import androidx.compose.runtime.Composable
import com.safeword.android.ui.navigation.SafeWordNavGraph

/**
 * Root composable for Safe Word Android.
 * Mirrors the React Router / window setup in desktop Safe Word.
 *
 * Uses NavHost with bottom navigation: Home, Models, Settings.
 */
@Composable
fun SafeWordAndroidApp() {
    SafeWordNavGraph()
}
