package com.sara.lucy

import com.sara.lucy.ui.LucyTheme
import com.sara.lucy.ui.MainWindow
import com.sara.lucy.ui.SpherePainter
import com.sara.lucy.win.Autostart
import com.sara.lucy.win.Hotkey
import com.sara.lucy.win.LucyLocation
import com.sara.lucy.win.LucyPaths
import com.sara.lucy.win.SingleInstance
import com.sara.lucy.win.Updater
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Lucy para Windows.
 *   (sin argumentos)       abre Lucy (si ya está abierta, la muestra)
 *   --hidden               arranca en la bandeja (inicio con Windows)
 *   --launcher <exe>       el .exe portable desde el que se abrió (lo pasa el lanzador)
 *   --replace <exe>        viene de una actualización: borra el .exe viejo
 *   --selftest <carpeta>   autoprueba (la usa la compilación en la nube)
 *   --write-icon <ico>     genera el ícono
 */
fun main(args: Array<String>) {
    // Vosk devuelve UTF-8: sin esto, en Windows «música» llega como «mÃºsica» y no se entiende.
    System.setProperty("jna.encoding", "UTF-8")
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    arg("--write-icon")?.let { writeIcon(File(it)); return }
    arg("--launcher")?.let { LucyLocation.portableExe = File(it) }
    arg("--selftest")?.let { exitProcess(SelfTest(File(it)).run()) }
    // Lucy Remota pregunta qué micrófonos hay (los mismos nombres que usa Lucy).
    if ("--microfonos" in args) {
        println(org.json.JSONArray(com.sara.lucy.audio.Microphone.available()).toString())
        return
    }
    if ("--embebida" in args) com.sara.lucy.win.Embedded.active = true

    val firstRun = !File(LucyPaths.home, "settings.json").exists()
    var window: MainWindow? = null
    val instance = SingleInstance(LucyPaths.home)
    val replacing = arg("--replace")
    // Al actualizar, la versión vieja tarda un momento en cerrarse.
    val acquired = (0 until if (replacing != null) 20 else 1).any { attempt ->
        if (attempt > 0) Thread.sleep(500)
        instance.acquire { window?.showWindow() }
    }
    if (!acquired) return

    LucyTheme.install()
    val app = LucyApp()
    replacing?.let { old -> Updater(app.logger).cleanup(File(old)) }
    if (com.sara.lucy.win.Embedded.active) Autostart.set(false) else if (firstRun) Autostart.set(true) else Autostart.refresh()
    app.logger.d("Lucy", "Inicio V${LucyLocation.version} (${LucyLocation.executable()})")

    SwingUtilities.invokeAndWait {
        val w = MainWindow(app)
        window = w
        app.start()
        if (app.settings.current.hotkey) Hotkey.register(app.logger) { SwingUtilities.invokeLater { w.talk() } }
        val embedded = com.sara.lucy.win.Embedded.active
        instance.onCommand = { cmd ->
            when (cmd) {
                "settings" -> { w.showWindow(); SwingUtilities.invokeLater { w.openSettings() } }
                "talk" -> SwingUtilities.invokeLater { w.talk() }
                "quit" -> SwingUtilities.invokeLater { w.exit() }
            }
        }
        val hidden = "--hidden" in args || (embedded && "--mostrar" !in args) || (app.settings.current.startMinimized && !firstRun && replacing == null)
        if (!hidden) w.showWindow()
        // Dentro de Lucy Remota, las actualizaciones las baja ella.
        if (!embedded) w.checkUpdates(Updater(app.logger))
    }
}

/** Ícono .ico con varios tamaños (PNG adentro, como los de Windows Vista en adelante). */
fun writeIcon(target: File) {
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.map { size -> ByteArrayOutputStream().also { ImageIO.write(SpherePainter.icon(size), "png", it) }.toByteArray() }
    val out = ByteArrayOutputStream()
    fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
    fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    short(0); short(1); short(sizes.size)
    var offset = 6 + 16 * sizes.size
    sizes.forEachIndexed { i, size ->
        out.write(if (size >= 256) 0 else size); out.write(if (size >= 256) 0 else size)
        out.write(0); out.write(0); short(1); short(32); int(pngs[i].size); int(offset)
        offset += pngs[i].size
    }
    pngs.forEach { out.write(it) }
    target.parentFile?.mkdirs()
    target.writeBytes(out.toByteArray())
}
