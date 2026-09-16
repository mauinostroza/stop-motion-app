package com.stopmotion.app.data

import android.content.Context

/**
 * Minimal service-locator for the [ProjectRepository]. Keeps the rest of
 * the app free from manual DI boilerplate. A real project would swap this
 * for Hilt or Koin.
 */
object ServiceLocator {
    @Volatile private var repo: ProjectRepository? = null

    fun provideRepository(context: Context): ProjectRepository {
        return repo ?: synchronized(this) {
            repo ?: ProjectRepository(context.applicationContext).also { repo = it }
        }
    }
}
