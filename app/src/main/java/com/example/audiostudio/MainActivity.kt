package com.example.audiostudio

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : Activity() {

    private val SR = 48000
    private val CH = 1

    private var original: FloatArray? = null
    private var processed: FloatArray? = null

    private var rec: AudioRecord? = null
    private var recThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var paused = false
    @Volatile private var recFrames = 0L
    private val chunks = ArrayList<FloatArray>()
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    private var trk: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var playFrame = 0

    @Volatile private var busy = false
    private var listenProcessed = false
    private var formatIndex = 0
    private var bitrateIndex = 3

    private val fmtName = arrayOf(
        "WAV 16 bit (CD)", "WAV 24 bit (estudio)", "WAV 32 bit float",
        "FLAC (sin perdida)", "M4A / AAC", "OGG / Opus"
    )
    private val fmtExt = arrayOf("wav", "wav", "wav", "flac", "m4a", "ogg")
    private val fmtMime = arrayOf(
        "audio/wav", "audio/wav", "audio/wav", "audio/flac", "audio/mp4", "audio/ogg"
    )
    private val bitrates = arrayOf(96, 128, 192, 256, 320)

    private lateinit var txtTime: TextView
    private lateinit var meter: ProgressBar
    private lateinit var btnRec: Button
    private lateinit var btnPause: Button
    private lateinit var cbHw: CheckBox
    private lateinit var boxTake: LinearLayout
    private lateinit var sbPos: SeekBar
    private lateinit var txtPos: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnSource: Button
    private lateinit var cbDenoise: CheckBox
    private lateinit var sbDenoise: SeekBar
    private lateinit var cbHigh: CheckBox
    private lateinit var cbHum: CheckBox
    private lateinit var cbDeEss: CheckBox
    private lateinit var cbComp: CheckBox
    private lateinit var sbComp: SeekBar
    private lateinit var sbWarm: SeekBar
    private lateinit var sbPres: SeekBar
    private lateinit var sbAir: SeekBar
    private lateinit var cbTrim: CheckBox
    private lateinit var btnEnhance: Button
    private lateinit var btnFormat: Button
    private lateinit var btnBitrate: Button
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var bar: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var lblDenoise: TextView
    private lateinit var lblComp: TextView
    private lateinit var lblWarm: TextView
    private lateinit var lblPres: TextView
    private lateinit var lblAir: TextView

    private val h = Handler(Looper.getMainLooper())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
        startTicker()
    }

    // ------------------------------------------------------------- UI ---

    private fun title(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 17f
        v.setTextColor(Color.WHITE)
        v.setPadding(0, dp(14), 0, dp(6))
        return v
    }

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(Color.parseColor("#FFBBBBCC"))
        return v
    }

    private fun check(t: String, on: Boolean): CheckBox {
        val c = CheckBox(this)
        c.text = t
        c.isChecked = on
        c.setTextColor(Color.WHITE)
        return c
    }

    private fun slider(maxV: Int, v: Int): SeekBar {
        val s = SeekBar(this)
        s.max = maxV
        s.progress = v
        return s
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(root)

        val head = TextView(this)
        head.text = "AUDIO STUDIO"
        head.textSize = 20f
        head.setTextColor(Color.parseColor("#FF4CC2FF"))
        root.addView(head)

        txtTime = TextView(this)
        txtTime.text = "00:00.0"
        txtTime.textSize = 44f
        txtTime.setTextColor(Color.WHITE)
        root.addView(txtTime)

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        meter.max = 100
        meter.progress = 0
        root.addView(meter, ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        root.addView(label("Nivel de entrada - 48 kHz / 32 bit float"))

        val rowRec = LinearLayout(this)
        rowRec.orientation = LinearLayout.HORIZONTAL
        btnRec = Button(this)
        btnRec.text = "GRABAR"
        btnRec.setOnClickListener { onRecClick() }
        rowRec.addView(btnRec)
        btnPause = Button(this)
        btnPause.text = "PAUSAR"
        btnPause.setOnClickListener { paused = !paused; refresh() }
        rowRec.addView(btnPause)
        root.addView(rowRec)

        cbHw = check("Filtro de ruido del telefono (voz)", false)
        root.addView(cbHw)

        boxTake = LinearLayout(this)
        boxTake.orientation = LinearLayout.VERTICAL
        root.addView(boxTake)

        boxTake.addView(title("PRE-ESCUCHA"))
        sbPos = slider(1000, 0)
        sbPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) txtPos.text = fmtTime(permilleToMs(p))
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                seekTo(permilleToMs(s!!.progress))
            }
        })
        boxTake.addView(sbPos)
        txtPos = label("00:00.0")
        boxTake.addView(txtPos)

        val rowPlay = LinearLayout(this)
        rowPlay.orientation = LinearLayout.HORIZONTAL
        btnPlay = Button(this)
        btnPlay.text = "REPRODUCIR"
        btnPlay.setOnClickListener { playPause() }
        rowPlay.addView(btnPlay)
        btnSource = Button(this)
        btnSource.text = "ORIGINAL"
        btnSource.setOnClickListener {
            if (processed != null) {
                listenProcessed = !listenProcessed
                stopPlay()
                sbPos.progress = 0
                refresh()
            }
        }
        rowPlay.addView(btnSource)
        boxTake.addView(rowPlay)

        boxTake.addView(title("MEJORAR / LIMPIAR / REMASTERIZAR"))

        cbDenoise = check("Reduccion de ruido de fondo", true)
        boxTake.addView(cbDenoise)
        lblDenoise = label("Intensidad: 70%")
        boxTake.addView(lblDenoise)
        sbDenoise = slider(100, 70)
        sbDenoise.setOnSeekBarChangeListener(simpleListener { p ->
            lblDenoise.text = "Intensidad: " + p + "%"
        })
        boxTake.addView(sbDenoise)

        cbHigh = check("Quitar retumbe y viento (80 Hz)", true)
        boxTake.addView(cbHigh)
        cbHum = check("Quitar zumbido electrico (50 Hz)", false)
        boxTake.addView(cbHum)
        cbDeEss = check("De-esser (suavizar las S)", true)
        boxTake.addView(cbDeEss)

        cbComp = check("Compresor (volumen parejo)", true)
        boxTake.addView(cbComp)
        lblComp = label("Fuerza del compresor: 50%")
        boxTake.addView(lblComp)
        sbComp = slider(100, 50)
        sbComp.setOnSeekBarChangeListener(simpleListener { p ->
            lblComp.text = "Fuerza del compresor: " + p + "%"
        })
        boxTake.addView(sbComp)

        lblWarm = label("Calidez (graves): +1.5 dB")
        boxTake.addView(lblWarm)
        sbWarm = slider(120, 75)
        sbWarm.setOnSeekBarChangeListener(simpleListener { p ->
            lblWarm.text = "Calidez (graves): " + dbText(p) + " dB"
        })
        boxTake.addView(sbWarm)

        lblPres = label("Presencia (voz): +2.5 dB")
        boxTake.addView(lblPres)
        sbPres = slider(120, 85)
        sbPres.setOnSeekBarChangeListener(simpleListener { p ->
            lblPres.text = "Presencia (voz): " + dbText(p) + " dB"
        })
        boxTake.addView(sbPres)

        lblAir = label("Aire (agudos): +1.5 dB")
        boxTake.addView(lblAir)
        sbAir = slider(120, 75)
        sbAir.setOnSeekBarChangeListener(simpleListener { p ->
            lblAir.text = "Aire (agudos): " + dbText(p) + " dB"
        })
        boxTake.addView(sbAir)

        cbTrim = check("Recortar silencios al inicio y final", true)
        boxTake.addView(cbTrim)

        btnEnhance = Button(this)
        btnEnhance.text = "APLICAR MEJORAS"
        btnEnhance.setOnClickListener { applyEnhance() }
        boxTake.addView(btnEnhance)

        boxTake.addView(title("EXPORTAR"))
        btnFormat = Button(this)
        btnFormat.setOnClickListener {
            formatIndex = (formatIndex + 1) % fmtName.size
            if (formatIndex == 5 && Build.VERSION.SDK_INT < 29) formatIndex = 0
            refresh()
        }
        boxTake.addView(btnFormat)

        btnBitrate = Button(this)
        btnBitrate.setOnClickListener {
            bitrateIndex = (bitrateIndex + 1) % bitrates.size
            refresh()
        }
        boxTake.addView(btnBitrate)

        val rowSave = LinearLayout(this)
        rowSave.orientation = LinearLayout.HORIZONTAL
        btnSave = Button(this)
        btnSave.text = "GUARDAR"
        btnSave.setOnClickListener { exportNow() }
        rowSave.addView(btnSave)
        btnDelete = Button(this)
        btnDelete.text = "ELIMINAR"
        btnDelete.setOnClickListener { discard() }
        rowSave.addView(btnDelete)
        boxTake.addView(rowSave)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, dp(14))

        txtStatus = TextView(this)
        txtStatus.text = "Listo para grabar"
        txtStatus.setTextColor(Color.parseColor("#FFDDDDEE"))
        txtStatus.setPadding(0, dp(10), 0, 0)
        root.addView(txtStatus)

        return scroll
    }

    private fun simpleListener(cb: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = cb(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
    }

    private fun dbText(p: Int): String {
        val v = (p - 60) / 10.0f
        return String.format(Locale.US, "%+.1f", v)
    }

    private fun dbValue(p: Int): Float = (p - 60) / 10.0f

    private fun status(t: String) {
        h.post { txtStatus.text = t }
    }

    private fun refresh() {
        val has = original != null && original!!.isNotEmpty()
        btnRec.text = if (recording) "DETENER" else "GRABAR"
        btnPause.visibility = if (recording) View.VISIBLE else View.GONE
        btnPause.text = if (paused) "REANUDAR" else "PAUSAR"
        cbHw.visibility = if (!recording && !has) View.VISIBLE else View.GONE
        boxTake.visibility = if (has && !recording) View.VISIBLE else View.GONE
        btnPlay.text = if (playing) "PAUSAR" else "REPRODUCIR"
        btnSource.visibility = if (processed != null) View.VISIBLE else View.GONE
        btnSource.text = if (listenProcessed) "MEJORADO" else "ORIGINAL"
        btnFormat.text = "Formato: " + fmtName[formatIndex]
        val lossy = formatIndex == 4 || formatIndex == 5
        btnBitrate.visibility = if (lossy) View.VISIBLE else View.GONE
        btnBitrate.text = "Calidad: " + bitrates[bitrateIndex] + " kbps"
        val on = !busy
        btnRec.isEnabled = on
        btnPlay.isEnabled = on
        btnEnhance.isEnabled = on
        btnSave.isEnabled = on
        btnDelete.isEnabled = on
        val d = durationMs()
        if (!recording) txtTime.text = fmtTime(d)
    }

    private fun startTicker() {
        h.postDelayed(object : Runnable {
            override fun run() {
                if (recording) {
                    txtTime.text = fmtTime(recFrames * 1000L / SR)
                }
                if (playing) {
                    val d = durationMs()
                    val pos = playFrame.toLong() * 1000L / SR
                    txtPos.text = fmtTime(pos)
                    if (d > 0) sbPos.progress = (pos * 1000L / d).toInt()
                }
                h.postDelayed(this, 100L)
            }
        }, 100L)
    }

    private fun fmtTime(ms: Long): String {
        val t = ms / 1000L
        return String.format(Locale.US, "%02d:%02d.%d", t / 60L, t % 60L, (ms % 1000L) / 100L)
    }

    private fun currentData(): FloatArray? {
        val p = processed
        return if (listenProcessed && p != null) p else original
    }

    private fun durationMs(): Long {
        val d = currentData() ?: return 0L
        return (d.size / CH).toLong() * 1000L / SR
    }

    private fun permilleToMs(p: Int): Long = durationMs() * p / 1000L

    // -------------------------------------------------------- GRABAR ---

    private fun onRecClick() {
        if (recording) {
            stopRecording()
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
                return
            }
            startRecording()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            status("Necesito permiso de microfono")
        }
    }

    private fun startRecording() {
        try {
            stopPlay()
            original = null
            processed = null
            listenProcessed = false
            chunks.clear()
            recFrames = 0L

            val mask = if (CH == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(SR, mask, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBuf <= 0) {
                status("El equipo no soporta 48 kHz float")
                return
            }
            val src = if (cbHw.isChecked) MediaRecorder.AudioSource.VOICE_RECOGNITION
            else MediaRecorder.AudioSource.MIC

            val r = AudioRecord(src, SR, mask, AudioFormat.ENCODING_PCM_FLOAT, minBuf * 4)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                status("No se pudo abrir el microfono")
                return
            }
            if (cbHw.isChecked) {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(r.audioSessionId)
                    ns?.enabled = true
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(r.audioSessionId)
                    agc?.enabled = true
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(r.audioSessionId)
                    aec?.enabled = true
                }
            }
            rec = r
            recording = true
            paused = false
            r.startRecording()
            status("Grabando...")
            refresh()

            recThread = thread(name = "rec") {
                val buf = FloatArray(4096 * CH)
                while (recording) {
                    val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    var peak = 0f
                    var i = 0
                    while (i < n) {
                        val a = abs(buf[i])
                        if (a > peak) peak = a
                        i++
                    }
                    val pk = (peak * 100f).toInt()
                    h.post { meter.progress = if (pk > 100) 100 else pk }
                    if (!paused) {
                        chunks.add(buf.copyOf(n))
                        recFrames += (n / CH).toLong()
                    }
                }
            }
        } catch (e: Throwable) {
            recording = false
            status("Error: " + e.message)
            refresh()
        }
    }

    private fun stopRecording() {
        recording = false
        try { recThread?.join(800) } catch (e: Throwable) { }
        recThread = null
        val r = rec
        if (r != null) {
            try { r.stop() } catch (e: Throwable) { }
            r.release()
        }
        rec = null
        ns?.release(); ns = null
        agc?.release(); agc = null
        aec?.release(); aec = null

        var total = 0
        for (c in chunks) total += c.size
        val out = FloatArray(total)
        var p = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, p, c.size)
            p += c.size
        }
        chunks.clear()
        original = out
        processed = null
        listenProcessed = false
        meter.progress = 0
        sbPos.progress = 0
        txtPos.text = "00:00.0"
        status(if (out.isEmpty()) "No se capturo audio" else "Escuchala antes de guardar")
        refresh()
    }

    // --------------------------------------------------- PRE-ESCUCHA ---

    private fun playPause() {
        if (playing) {
            stopPlay()
            refresh()
            return
        }
        val data = currentData() ?: return
        if (data.isEmpty()) return
        val total = data.size / CH
        var start = (permilleToMs(sbPos.progress) * SR / 1000L).toInt()
        if (start >= total - 10) start = 0
        startPlay(data, start)
        refresh()
    }

    private fun startPlay(data: FloatArray, startFrame: Int) {
        stopPlay()
        val mask = if (CH == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(SR, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val af = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SR)
            .setChannelMask(mask)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(af)
            .setBufferSizeInBytes(max(minBuf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trk = t
        playing = true
        playFrame = startFrame
        t.play()
        val total = data.size / CH
        playThread = thread(name = "play") {
            var f = startFrame
            try {
                while (playing && f < total) {
                    val n = min(2048, total - f)
                    val w = t.write(data, f * CH, n * CH, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) break
                    f += w / CH
                    playFrame = f
                }
            } catch (e: Throwable) {
            } finally {
                val ended = f >= total
                playing = false
                try { t.stop() } catch (e: Throwable) { }
                t.release()
                if (trk === t) trk = null
                h.post {
                    if (ended) {
                        sbPos.progress = 0
                        playFrame = 0
                        txtPos.text = "00:00.0"
                    }
                    refresh()
                }
            }
        }
    }

    private fun stopPlay() {
        playing = false
        try { playThread?.join(500) } catch (e: Throwable) { }
        playThread = null
        val t = trk
        if (t != null) {
            try { t.stop() } catch (e: Throwable) { }
            t.release()
        }
        trk = null
    }

    private fun seekTo(ms: Long) {
        val data = currentData() ?: return
        val frame = (ms * SR / 1000L).toInt()
        playFrame = frame
        txtPos.text = fmtTime(ms)
        if (playing) startPlay(data, frame)
    }

    // ------------------------------------------------------- MEJORAR ---

    private fun applyEnhance() {
        val src = original ?: return
        if (busy) return
        stopPlay()
        busy = true
        refresh()
        status("Mejorando audio...")
        val f = Fx()
        f.denoise = cbDenoise.isChecked
        f.denoiseAmount = sbDenoise.progress / 100f
        f.highPass = cbHigh.isChecked
        f.hum = cbHum.isChecked
        f.deEsser = cbDeEss.isChecked
        f.compress = cbComp.isChecked
        f.compressAmount = sbComp.progress / 100f
        f.warmthDb = dbValue(sbWarm.progress)
        f.presenceDb = dbValue(sbPres.progress)
        f.airDb = dbValue(sbAir.progress)
        f.trimSilence = cbTrim.isChecked

        thread(name = "dsp") {
            val result = try {
                enhance(src.copyOf(), CH, SR, f) { p ->
                    h.post { bar.progress = (p * 100).toInt() }
                }
            } catch (e: Throwable) {
                null
            }
            h.post {
                busy = false
                bar.progress = 100
                if (result == null) {
                    status("Error al mejorar el audio")
                } else {
                    processed = result
                    listenProcessed = true
                    sbPos.progress = 0
                    txtPos.text = "00:00.0"
                    status("Listo. Toca ORIGINAL / MEJORADO para comparar")
                }
                refresh()
            }
        }
    }

    // ------------------------------------------------------ EXPORTAR ---

    private fun exportNow() {
        val data = currentData() ?: return
        if (busy) return
        stopPlay()
        busy = true
        refresh()
        status("Exportando...")
        val fi = formatIndex
        val kbps = bitrates[bitrateIndex]

        thread(name = "exp") {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val base = "Grabacion_" + stamp
                val tmp = File(cacheDir, base + "." + fmtExt[fi])
                val written = exportAudio(data, CH, SR, fi, kbps, tmp) { p ->
                    h.post { bar.progress = (p * 100).toInt() }
                }
                val where = saveToMusic(written, written.name, fmtMime[fi])
                written.delete()
                h.post {
                    busy = false
                    bar.progress = 100
                    status("Guardado en " + where)
                    refresh()
                }
            } catch (e: Throwable) {
                h.post {
                    busy = false
                    status("Error al exportar: " + e.message)
                    refresh()
                }
            }
        }
    }

    private fun discard() {
        stopPlay()
        original = null
        processed = null
        listenProcessed = false
        sbPos.progress = 0
        txtPos.text = "00:00.0"
        bar.progress = 0
        status("Grabacion eliminada")
        refresh()
    }

    private fun exportAudio(
        samples: FloatArray, ch: Int, sr: Int, fi: Int, kbps: Int,
        outFile: File, onProgress: (Float) -> Unit
    ): File {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()
        try {
            when (fi) {
                0 -> writeWav(outFile, samples, ch, sr, 16, false, onProgress)
                1 -> writeWav(outFile, samples, ch, sr, 24, false, onProgress)
                2 -> writeWav(outFile, samples, ch, sr, 32, true, onProgress)
                3 -> encodeFlac(samples, ch, sr, outFile, onProgress)
                4 -> encodeMuxed(
                    samples, ch, sr, MediaFormat.MIMETYPE_AUDIO_AAC, kbps * 1000,
                    outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, onProgress
                )
                else -> encodeMuxed(
                    samples, ch, sr, "audio/opus", kbps * 1000,
                    outFile, 2, onProgress
                )
            }
            return outFile
        } catch (e: Throwable) {
            val fb = File(outFile.parentFile, outFile.nameWithoutExtension + ".wav")
            writeWav(fb, samples, ch, sr, 16, false, onProgress)
            return fb
        }
    }

    private fun writeWav(
        file: File, samples: FloatArray, ch: Int, sr: Int,
        bits: Int, isFloat: Boolean, onProgress: (Float) -> Unit
    ) {
        val bps = bits / 8
        val dataSize = samples.size * bps
        val blockAlign = ch * bps
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray())
        hdr.putInt(36 + dataSize)
        hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray())
        hdr.putInt(16)
        hdr.putShort(if (isFloat) 3.toShort() else 1.toShort())
        hdr.putShort(ch.toShort())
        hdr.putInt(sr)
        hdr.putInt(sr * blockAlign)
        hdr.putShort(blockAlign.toShort())
        hdr.putShort(bits.toShort())
        hdr.put("data".toByteArray())
        hdr.putInt(dataSize)

        val out = BufferedOutputStream(FileOutputStream(file), 65536)
        try {
            out.write(hdr.array())
            val chunk = 8192
            val buf = ByteBuffer.allocate(chunk * bps).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                buf.clear()
                val end = min(i + chunk, samples.size)
                while (i < end) {
                    var v = samples[i]
                    if (v > 1f) v = 1f
                    if (v < -1f) v = -1f
                    if (isFloat) {
                        buf.putFloat(v)
                    } else if (bits == 16) {
                        buf.putShort((v * 32767f).roundToInt().toShort())
                    } else {
                        val x = (v * 8388607f).roundToInt()
                        buf.put((x and 0xFF).toByte())
                        buf.put(((x shr 8) and 0xFF).toByte())
                        buf.put(((x shr 16) and 0xFF).toByte())
                    }
                    i++
                }
                out.write(buf.array(), 0, buf.position())
                if (samples.isNotEmpty()) onProgress(i.toFloat() / samples.size)
            }
        } finally {
            out.close()
        }
        onProgress(1f)
    }

    private fun encodeFlac(
        samples: FloatArray, ch: Int, sr: Int, outFile: File, onProgress: (Float) -> Unit
    ) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sr, ch)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, sr * ch * 16)
        fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = BufferedOutputStream(FileOutputStream(outFile), 65536)
        try {
            runCodec(codec, samples, ch, sr, onProgress, { },
                { buffer, info, isConfig ->
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    out.write(bytes)
                })
        } finally {
            out.close()
        }
        onProgress(1f)
    }

    private fun encodeMuxed(
        samples: FloatArray, ch: Int, sr: Int, mime: String, bitRate: Int,
        outFile: File, muxerFormat: Int, onProgress: (Float) -> Unit
    ) {
        val codec = MediaCodec.createEncoderByType(mime)
        val fmt = MediaFormat.createAudioFormat(mime, sr, ch)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(outFile.absolutePath, muxerFormat)
        var track = -1
        var started = false
        runCodec(codec, samples, ch, sr, onProgress,
            { f ->
                track = muxer.addTrack(f)
                muxer.start()
                started = true
            },
            { buffer, info, isConfig ->
                if (!isConfig && started && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buffer, info)
                }
            })
        if (started) {
            try { muxer.stop() } catch (e: Throwable) { }
        }
        muxer.release()
        onProgress(1f)
    }

    private fun runCodec(
        codec: MediaCodec, samples: FloatArray, ch: Int, sr: Int,
        onProgress: (Float) -> Unit,
        onFormat: (MediaFormat) -> Unit,
        onData: (ByteBuffer, MediaCodec.BufferInfo, Boolean) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        val totalFrames = samples.size / ch
        val bpf = 2 * ch
        var fed = 0
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000L)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)
                        if (buf != null) {
                            buf.clear()
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            val cap = buf.capacity() / bpf
                            val n = min(cap, totalFrames - fed)
                            val pts = fed.toLong() * 1000000L / sr
                            if (n > 0) {
                                var i = fed * ch
                                val end = i + n * ch
                                while (i < end) {
                                    var v = samples[i]
                                    if (v > 1f) v = 1f
                                    if (v < -1f) v = -1f
                                    buf.putShort((v * 32767f).roundToInt().toShort())
                                    i++
                                }
                                codec.queueInputBuffer(ii, 0, n * bpf, pts, 0)
                                fed += n
                                if (totalFrames > 0) onProgress(0.95f * fed / totalFrames)
                            } else {
                                codec.queueInputBuffer(
                                    ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inDone = true
                            }
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000L)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormat(codec.outputFormat)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (ob != null && info.size > 0) onData(ob, info, isCfg)
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (e: Throwable) { }
            codec.release()
        }
    }

    private fun saveToMusic(src: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues()
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            cv.put(MediaStore.Audio.Media.MIME_TYPE, mime)
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AudioStudio")
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1)
            val cr = contentResolver
            val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("no se pudo crear")
            val os = cr.openOutputStream(uri) ?: throw IllegalStateException("no se pudo escribir")
            os.use { o -> src.inputStream().use { i -> i.copyTo(o) } }
            cv.clear()
            cv.put(MediaStore.Audio.Media.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
            return "Musica/AudioStudio/" + name
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "AudioStudio"
        )
        dir.mkdirs()
        val dst = File(dir, name)
        src.copyTo(dst, true)
        return dst.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        recording = false
        stopPlay()
    }
}

// =========================== MOTOR DE AUDIO ===========================

class Fx {
    var trimSilence = true
    var highPass = true
    var hum = false
    var denoise = true
    var denoiseAmount = 0.7f
    var deEsser = true
    var compress = true
    var compressAmount = 0.5f
    var warmthDb = 1.5f
    var presenceDb = 2.5f
    var airDb = 1.5f
}

class Bq(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

fun enhance(input: FloatArray, ch: Int, sr: Int, f: Fx, onProgress: (Float) -> Unit): FloatArray {
    if (input.isEmpty()) return input
    val chans = split(input, ch)
    for (c in chans.indices) {
        var x = chans[c]
        if (f.highPass) x = bq(x, hp(sr, 80f, 0.707f))
        if (f.hum) {
            var fr = 50f
            while (fr < 320f) {
                x = bq(x, notch(sr, fr, 20f))
                fr += 50f
            }
        }
        if (f.denoise) x = denoise(x, f.denoiseAmount)
        if (f.deEsser) x = deEss(x, sr)
        if (f.warmthDb != 0f) x = bq(x, lowShelf(sr, 180f, f.warmthDb))
        if (f.presenceDb != 0f) x = bq(x, peak(sr, 3400f, 1.0f, f.presenceDb))
        if (f.airDb != 0f) x = bq(x, highShelf(sr, 9000f, f.airDb))
        chans[c] = x
        onProgress((c + 1).toFloat() / ch * 0.85f)
    }
    var out = join(chans, ch)
    if (f.compress) out = comp(out, ch, sr, f.compressAmount)
    onProgress(0.9f)
    out = normalize(out, -1.0f)
    out = limit(out, ch, sr)
    if (f.trimSilence) out = trim(out, ch, sr)
    fade(out, ch, sr, 8)
    onProgress(1f)
    return out
}

fun split(x: FloatArray, ch: Int): Array<FloatArray> {
    if (ch == 1) return arrayOf(x.copyOf())
    val fr = x.size / ch
    return Array(ch) { c -> FloatArray(fr) { i -> x[i * ch + c] } }
}

fun join(a: Array<FloatArray>, ch: Int): FloatArray {
    if (ch == 1) return a[0]
    val fr = a[0].size
    val out = FloatArray(fr * ch)
    for (c in 0 until ch) {
        for (i in 0 until fr) out[i * ch + c] = a[c][i]
    }
    return out
}

fun nrm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): Bq {
    return Bq(
        (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
        (a1 / a0).toFloat(), (a2 / a0).toFloat()
    )
}

fun hp(sr: Int, f: Float, q: Float): Bq {
    val w = 2.0 * PI * f / sr
    val cw = cos(w)
    val al = sin(w) / (2.0 * q)
    return nrm((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
}

fun notch(sr: Int, f: Float, q: Float): Bq {
    val w = 2.0 * PI * f / sr
    val cw = cos(w)
    val al = sin(w) / (2.0 * q)
    return nrm(1.0, -2 * cw, 1.0, 1 + al, -2 * cw, 1 - al)
}

fun peak(sr: Int, f: Float, q: Float, db: Float): Bq {
    val a = 10.0.pow(db / 40.0)
    val w = 2.0 * PI * f / sr
    val cw = cos(w)
    val al = sin(w) / (2.0 * q)
    return nrm(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
}

fun lowShelf(sr: Int, f: Float, db: Float): Bq {
    val a = 10.0.pow(db / 40.0)
    val w = 2.0 * PI * f / sr
    val cw = cos(w)
    val al = sin(w) / 2.0 * sqrt(2.0)
    val s2 = 2.0 * sqrt(a) * al
    return nrm(
        a * ((a + 1) - (a - 1) * cw + s2),
        2 * a * ((a - 1) - (a + 1) * cw),
        a * ((a + 1) - (a - 1) * cw - s2),
        (a + 1) + (a - 1) * cw + s2,
        -2 * ((a - 1) + (a + 1) * cw),
        (a + 1) + (a - 1) * cw - s2
    )
}

fun highShelf(sr: Int, f: Float, db: Float): Bq {
    val a = 10.0.pow(db / 40.0)
    val w = 2.0 * PI * f / sr
    val cw = cos(w)
    val al = sin(w) / 2.0 * sqrt(2.0)
    val s2 = 2.0 * sqrt(a) * al
    return nrm(
        a * ((a + 1) + (a - 1) * cw + s2),
        -2 * a * ((a - 1) + (a + 1) * cw),
        a * ((a + 1) + (a - 1) * cw - s2),
        (a + 1) - (a - 1) * cw + s2,
        2 * ((a - 1) - (a + 1) * cw),
        (a + 1) - (a - 1) * cw - s2
    )
}

fun bq(x: FloatArray, f: Bq): FloatArray {
    var x1 = 0f
    var x2 = 0f
    var y1 = 0f
    var y2 = 0f
    val out = FloatArray(x.size)
    for (i in x.indices) {
        val xn = x[i]
        val y = f.b0 * xn + f.b1 * x1 + f.b2 * x2 - f.a1 * y1 - f.a2 * y2
        x2 = x1
        x1 = xn
        y2 = y1
        y1 = y
        out[i] = y
    }
    return out
}

fun denoise(x: FloatArray, amount: Float): FloatArray {
    val n = 1024
    val hop = 256
    if (x.size < n * 3) return x
    val win = FloatArray(n)
    for (i in 0 until n) win[i] = (0.5 - 0.5 * cos(2.0 * PI * i / n)).toFloat()
    val bins = n / 2 + 1
    val re = FloatArray(n)
    val im = FloatArray(n)
    val noise = FloatArray(bins)
    val sm = FloatArray(bins)
    for (b in 0 until bins) noise[b] = Float.MAX_VALUE
    var first = true
    var pos = 0
    while (pos + n <= x.size) {
        for (i in 0 until n) {
            re[i] = x[pos + i] * win[i]
            im[i] = 0f
        }
        fft(re, im)
        for (b in 0 until bins) {
            val m = hypot(re[b].toDouble(), im[b].toDouble()).toFloat()
            sm[b] = if (first) m else 0.7f * sm[b] + 0.3f * m
            if (sm[b] < noise[b]) noise[b] = sm[b]
        }
        first = false
        pos += hop
    }
    val over = 1.0f + 2.5f * amount
    val fl = max(0.03f, 0.30f * (1f - amount))
    val out = FloatArray(x.size)
    pos = 0
    while (pos + n <= x.size) {
        for (i in 0 until n) {
            re[i] = x[pos + i] * win[i]
            im[i] = 0f
        }
        fft(re, im)
        for (b in 0 until bins) {
            val mag = hypot(re[b].toDouble(), im[b].toDouble()).toFloat()
            if (mag > 1e-9f) {
                var g = (mag - over * noise[b]) / mag
                if (g < fl) g = fl
                re[b] = re[b] * g
                im[b] = im[b] * g
                if (b > 0 && b < n / 2) {
                    re[n - b] = re[n - b] * g
                    im[n - b] = im[n - b] * g
                }
            }
        }
        ifft(re, im)
        for (i in 0 until n) out[pos + i] = out[pos + i] + re[i] * win[i] / 1.5f
        pos += hop
    }
    for (i in 0 until n) out[i] = x[i]
    var k = max(0, out.size - n)
    while (k < out.size) {
        out[k] = x[k]
        k++
    }
    return out
}

fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            var t = re[i]; re[i] = re[j]; re[j] = t
            t = im[i]; im[i] = im[j]; im[j] = t
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wr = cos(ang).toFloat()
        val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var cr = 1f
            var ci = 0f
            for (k in 0 until len / 2) {
                val ur = re[i + k]
                val ui = im[i + k]
                val br = re[i + k + len / 2]
                val bi = im[i + k + len / 2]
                val vr = br * cr - bi * ci
                val vi = br * ci + bi * cr
                re[i + k] = ur + vr
                im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr
                im[i + k + len / 2] = ui - vi
                val ncr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = ncr
            }
            i += len
        }
        len = len shl 1
    }
}

fun ifft(re: FloatArray, im: FloatArray) {
    for (i in im.indices) im[i] = -im[i]
    fft(re, im)
    val n = re.size
    for (i in re.indices) {
        re[i] = re[i] / n
        im[i] = -im[i] / n
    }
}

fun deEss(x: FloatArray, sr: Int): FloatArray {
    val hi = bq(x, hp(sr, 5500f, 0.707f))
    val out = FloatArray(x.size)
    var env = 0f
    val atk = exp(-1.0 / (0.001 * sr)).toFloat()
    val rel = exp(-1.0 / (0.060 * sr)).toFloat()
    val thr = 0.06f
    for (i in x.indices) {
        val a = abs(hi[i])
        env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
        var g = 1f
        if (env > thr) {
            g = thr / env
            if (g < 0.25f) g = 0.25f
        }
        out[i] = x[i] - (1f - g) * hi[i]
    }
    return out
}

fun comp(x: FloatArray, ch: Int, sr: Int, amount: Float): FloatArray {
    val thr = -24f + 8f * (1f - amount)
    val ratio = 1.5f + 4.5f * amount
    val atk = exp(-1.0 / (0.010 * sr)).toFloat()
    val rel = exp(-1.0 / (0.180 * sr)).toFloat()
    val mk = 10f.pow((-thr * (1f - 1f / ratio) * 0.6f) / 20f)
    val fr = x.size / ch
    var env = 0f
    val out = FloatArray(x.size)
    for (i in 0 until fr) {
        var pk = 0f
        for (c in 0 until ch) {
            val a = abs(x[i * ch + c])
            if (a > pk) pk = a
        }
        env = if (pk > env) atk * env + (1 - atk) * pk else rel * env + (1 - rel) * pk
        var e = env
        if (e < 1e-7f) e = 1e-7f
        val db = 20f * log10(e)
        val gdb = if (db > thr) -(db - thr) * (1f - 1f / ratio) else 0f
        val g = 10f.pow(gdb / 20f) * mk
        for (c in 0 until ch) out[i * ch + c] = x[i * ch + c] * g
    }
    return out
}

fun normalize(x: FloatArray, targetDb: Float): FloatArray {
    var pk = 0f
    for (v in x) {
        val a = abs(v)
        if (a > pk) pk = a
    }
    if (pk < 1e-6f) return x
    val g = 10f.pow(targetDb / 20f) / pk
    for (i in x.indices) x[i] = x[i] * g
    return x
}

fun limit(x: FloatArray, ch: Int, sr: Int): FloatArray {
    val ceil = 0.98f
    val rel = exp(-1.0 / (0.050 * sr)).toFloat()
    var g = 1f
    val fr = x.size / ch
    for (i in 0 until fr) {
        var pk = 0f
        for (c in 0 until ch) {
            val a = abs(x[i * ch + c])
            if (a > pk) pk = a
        }
        val need = if (pk > 0f && pk * g > ceil) ceil / pk else 1f
        g = if (need < g) need else g * rel + need * (1 - rel)
        for (c in 0 until ch) {
            var v = x[i * ch + c] * g
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            x[i * ch + c] = v
        }
    }
    return x
}

fun trim(x: FloatArray, ch: Int, sr: Int): FloatArray {
    val fr = x.size / ch
    if (fr < 10) return x
    var pk = 0f
    for (v in x) {
        val a = abs(v)
        if (a > pk) pk = a
    }
    val thr = max(pk * 0.008f, 0.0008f)
    var s = 0
    while (s < fr) {
        var p = 0f
        for (c in 0 until ch) {
            val a = abs(x[s * ch + c])
            if (a > p) p = a
        }
        if (p > thr) break
        s++
    }
    var e = fr - 1
    while (e > s) {
        var p = 0f
        for (c in 0 until ch) {
            val a = abs(x[e * ch + c])
            if (a > p) p = a
        }
        if (p > thr) break
        e--
    }
    if (e <= s) return x
    val pad = (sr * 0.12f).toInt()
    val a0 = max(0, s - pad)
    val b0 = min(fr - 1, e + pad)
    return x.copyOfRange(a0 * ch, (b0 + 1) * ch)
}

fun fade(x: FloatArray, ch: Int, sr: Int, ms: Int) {
    val fr = x.size / ch
    var n = sr * ms / 1000
    if (n > fr / 2) n = fr / 2
    for (i in 0 until n) {
        val g = i.toFloat() / n
        for (c in 0 until ch) {
            x[i * ch + c] = x[i * ch + c] * g
            x[(fr - 1 - i) * ch + c] = x[(fr - 1 - i) * ch + c] * g
        }
    }
}
