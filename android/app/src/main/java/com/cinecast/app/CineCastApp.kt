package com.cinecast.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.cinecast.app.cast.DlnaController
import com.cinecast.app.data.engine.LiveParser
import com.cinecast.app.data.engine.TvBoxConfigParser
import com.cinecast.app.data.engine.VodSourceEngine
import com.cinecast.app.data.network.NetworkClient
import com.cinecast.app.data.repository.AppRepository

class CineCastApp : Application() {

    lateinit var repository: AppRepository
        private set
    lateinit var vodEngine: VodSourceEngine
        private set
    lateinit var tvBoxParser: TvBoxConfigParser
        private set
    lateinit var liveParser: LiveParser
        private set
    lateinit var dlnaController: DlnaController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient { NetworkClient.client }
                .crossfade(true)
                .build()
        )
        repository = AppRepository(this)
        vodEngine = VodSourceEngine()
        tvBoxParser = TvBoxConfigParser()
        liveParser = LiveParser()
        dlnaController = DlnaController(this)
    }

    companion object {
        lateinit var instance: CineCastApp
            private set
    }
}
