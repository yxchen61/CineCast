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
import com.cinecast.app.music.engine.MusicSourceEngine
import com.cinecast.app.music.engine.MusicSourceParser
import com.cinecast.app.music.player.MusicAudioController
import com.cinecast.app.music.repository.MusicRepository
import com.cinecast.app.novel.engine.LegadoSourceParser
import com.cinecast.app.novel.engine.NovelSourceEngine
import com.cinecast.app.novel.repository.NovelRepository

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

    // 独立小说阅读模块依赖（与影视、音乐完全隔离）
    lateinit var novelRepository: NovelRepository
        private set
    lateinit var novelEngine: NovelSourceEngine
        private set
    lateinit var legadoParser: LegadoSourceParser
        private set

    // 独立音乐播放模块依赖（与影视、小说完全隔离）
    lateinit var musicRepository: MusicRepository
        private set
    lateinit var musicEngine: MusicSourceEngine
        private set
    lateinit var musicSourceParser: MusicSourceParser
        private set
    lateinit var musicAudioController: MusicAudioController
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

        novelRepository = NovelRepository(this)
        novelEngine = NovelSourceEngine()
        legadoParser = LegadoSourceParser()

        musicRepository = MusicRepository(this)
        musicEngine = MusicSourceEngine()
        musicSourceParser = MusicSourceParser()
        musicAudioController = MusicAudioController(this)
    }

    companion object {
        lateinit var instance: CineCastApp
            private set
    }
}
