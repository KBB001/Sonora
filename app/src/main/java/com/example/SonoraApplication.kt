package com.example

import android.app.Application
import com.example.data.remote.NewPipeDownloader
import dagger.hilt.android.HiltAndroidApp
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import javax.inject.Inject

@HiltAndroidApp
class SonoraApplication : Application() {

    @Inject
    lateinit var newPipeDownloader: NewPipeDownloader

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(newPipeDownloader, Localization.DEFAULT, ContentCountry.DEFAULT)
    }
}
