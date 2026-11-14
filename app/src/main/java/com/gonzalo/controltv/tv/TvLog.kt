package com.gonzalo.controltv.tv

/** Donde la capa del protocolo anota lo que manda y recibe. En la app es el log de Ajustes. */
fun interface TvLog {
    fun log(message: String)
}
