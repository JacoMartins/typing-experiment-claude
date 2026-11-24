package dev.trechos

import android.content.Context
import android.content.SharedPreferences

/** Preferências compartilhadas entre o teclado (serviço) e a tela de configurações. */
object Prefs {
    const val FILE = "trechos"

    const val MODE = "modo"
    const val RESET = "reset"
    const val CLIP_RESET = "clip_reset"
    const val LEARN = "aprender"
    const val HAPTIC = "vibrar"
    const val PREVIEW = "previa"

    /** Altura do teclado: 0.8 a 1.3 (1.0 = padrão). */
    const val HEIGHT = "altura"

    /** Posição: [LAYOUT_NORMAL], [LAYOUT_LEFT], [LAYOUT_RIGHT] ou [LAYOUT_FLOATING]. */
    const val LAYOUT = "posicao"
    const val LAYOUT_NORMAL = "normal"
    const val LAYOUT_LEFT = "esquerda"
    const val LAYOUT_RIGHT = "direita"
    const val LAYOUT_FLOATING = "flutuante"

    /** Modo flutuante: largura (fração da tela) e posição salva (px; -1 = padrão). */
    const val FLOAT_WIDTH = "flutuante_largura"
    const val FLOAT_X = "flutuante_x"
    const val FLOAT_Y = "flutuante_y"

    /** Largura do teclado no modo de uma mão (fração da tela). */
    const val ONE_HAND_WIDTH = 0.82f

    /** Preferências que mudam o layout do teclado (exigem recriar a View). */
    val LAYOUT_KEYS = setOf(HEIGHT, LAYOUT, FLOAT_WIDTH, PREVIEW)

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
