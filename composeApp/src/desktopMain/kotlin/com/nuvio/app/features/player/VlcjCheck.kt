package com.nuvio.app.features.player

import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

fun checkVlcjType() {
    val component = CallbackMediaPlayerComponent()
    val mp: EmbeddedMediaPlayer = component.mediaPlayer()
    println("EmbeddedMediaPlayer: $mp")
}
