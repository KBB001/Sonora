package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.remote.NewPipeDownloader
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class SonoraApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var newPipeDownloader: NewPipeDownloader

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(newPipeDownloader, Localization.DEFAULT, ContentCountry.DEFAULT)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(50L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(150)
            .build()
    }
}
