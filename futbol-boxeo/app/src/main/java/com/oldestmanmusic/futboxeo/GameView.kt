package com.oldestmanmusic.futboxeo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dibuja el partido con Canvas y traduce los toques en mandos:
 * mitad izquierda = joystick, mitad derecha = botones PASE / TIRO / PUÑO / ESQUIVA.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private val game = Game()
    @Volatile private var running = false
    private var thread: Thread? = null

    // Transformación mundo -> pantalla
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var viewW = 1f
    private var viewH = 1f

    // Joystick
    private var joyId = -1
    private var joyCx = 0f
    private var joyCy = 0f
    private var joyX = 0f
    private var joyY = 0f
    private var joyR = 100f

    // Botones
    private class Button(val label: String, val color: Int) {
        var x = 0f
        var y = 0f
        var r = 0f
        var pointer = -1
    }
    private val btnPunch = Button("PUÑO", Color.rgb(229, 57, 53))
    private val btnShoot = Button("TIRO", Color.rgb(251, 140, 0))
    private val btnPass = Button("PASE", Color.rgb(30, 136, 229))
    private val btnDodge = Button("ESQUIVA", Color.rgb(67, 160, 71))
    private val buttons = listOf(btnPunch, btnShoot, btnPass, btnDodge)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val rnd = Random(7)

    private var tones: ToneGenerator? = null
    private var pendingHaptic = -1

    private val teamColor = intArrayOf(Color.rgb(30, 136, 229), Color.rgb(229, 57, 53))
    private val teamDark = intArrayOf(Color.rgb(13, 71, 161), Color.rgb(183, 28, 28))
    private val keeperColor = intArrayOf(Color.rgb(0, 172, 193), Color.rgb(255, 160, 0))

    init {
        holder.addCallback(this)
        isFocusable = true
        isHapticFeedbackEnabled = true
        game.onEvent = { onGameEvent(it) }
    }

    // ----------------------------------------------------------- ciclo de vida

    override fun surfaceCreated(holder: SurfaceHolder) = resume()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        layout(width.toFloat(), height.toFloat())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = pause()

    fun resume() {
        if (running || !holder.surface.isValid) return
        if (tones == null) tones = try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (e: RuntimeException) { null }
        running = true
        thread = Thread(this, "futboxeo-loop").also { it.start() }
    }

    fun pause() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) { }
        thread = null
        tones?.release()
        tones = null
        game.input.mx = 0f; game.input.my = 0f
        joyId = -1
    }

    private fun layout(w: Float, h: Float) {
        viewW = w; viewH = h
        val hud = h * 0.11f
        val pad = 40f
        scale = min(w / (Cfg.W + pad * 2), (h - hud) / (Cfg.H + pad * 1.2f))
        ox = (w - Cfg.W * scale) / 2
        oy = hud + (h - hud - Cfg.H * scale) / 2
        joyR = h * 0.13f
        val br = h * 0.085f
        val bx = w - br * 1.6f
        val by = h - br * 1.6f
        place(btnPunch, bx, by, br * 1.15f)
        place(btnShoot, bx - br * 2.5f, by + br * 0.15f, br)
        place(btnDodge, bx + br * 0.1f, by - br * 2.45f, br * 0.9f)
        place(btnPass, bx - br * 2.3f, by - br * 2.2f, br)
    }

    private fun place(b: Button, x: Float, y: Float, r: Float) { b.x = x; b.y = y; b.r = r }

    override fun run() {
        val step = 1f / 60f
        var last = System.nanoTime()
        var acc = 0f
        while (running) {
            val now = System.nanoTime()
            acc += min(0.1f, (now - last) / 1e9f)
            last = now
            synchronized(game) {
                while (acc >= step) { game.update(step); acc -= step }
            }
            val h = pendingHaptic
            if (h >= 0) { pendingHaptic = -1; post { performHapticFeedback(h) } }
            val canvas = try { holder.lockCanvas() } catch (e: Exception) { null } ?: continue
            try {
                synchronized(game) { render(canvas) }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
            }
            val spent = (System.nanoTime() - now) / 1_000_000L
            if (spent < 15) try { Thread.sleep(15 - spent) } catch (_: InterruptedException) { }
        }
    }

    private fun onGameEvent(e: GameEvent) {
        if (game.onTitle) return
        val t = tones
        when (e) {
            GameEvent.HIT -> pendingHaptic = HapticFeedbackConstants.VIRTUAL_KEY
            GameEvent.KO -> { pendingHaptic = HapticFeedbackConstants.LONG_PRESS; t?.startTone(ToneGenerator.TONE_PROP_NACK, 120) }
            GameEvent.GOAL_HUMAN -> { pendingHaptic = HapticFeedbackConstants.LONG_PRESS; t?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) }
            GameEvent.GOAL_CPU -> t?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            GameEvent.WHISTLE -> t?.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            GameEvent.KICK -> { }
        }
    }

    // ---------------------------------------------------------------- toques

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val input = game.input
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                input.tap = true
                val b = buttons.firstOrNull { hypot(x - it.x, y - it.y) < it.r * 1.25f }
                if (b != null) {
                    b.pointer = id
                    when (b) {
                        btnPunch -> input.punch = true
                        btnShoot -> input.shoot = true
                        btnPass -> input.pass = true
                        btnDodge -> input.dodge = true
                    }
                } else if (x < viewW * 0.5f && joyId == -1) {
                    joyId = id
                    joyCx = x; joyCy = y
                    joyX = x; joyY = y
                    updateJoy()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == joyId) {
                        joyX = e.getX(i); joyY = e.getY(i)
                        updateJoy()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) listOf(e.getPointerId(e.actionIndex))
                else (0 until e.pointerCount).map { e.getPointerId(it) }
                for (id in ids) {
                    if (id == joyId) { joyId = -1; input.mx = 0f; input.my = 0f }
                    buttons.forEach { if (it.pointer == id) it.pointer = -1 }
                }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    joyId = -1; input.mx = 0f; input.my = 0f
                    buttons.forEach { it.pointer = -1 }
                }
            }
        }
        return true
    }

    private fun updateJoy() {
        var dx = joyX - joyCx
        var dy = joyY - joyCy
        val d = hypot(dx, dy)
        if (d > joyR) {
            // El centro "persigue" al dedo para que no haya que volver atrás.
            joyCx = joyX - dx / d * joyR
            joyCy = joyY - dy / d * joyR
            dx = joyX - joyCx; dy = joyY - joyCy
        }
        val m = hypot(dx, dy) / joyR
        if (m < 0.12f) { game.input.mx = 0f; game.input.my = 0f; return }
        game.input.mx = dx / joyR
        game.input.my = dy / joyR
    }

    // ---------------------------------------------------------------- dibujo

    private fun sx(x: Float) = ox + x * scale
    private fun sy(y: Float) = oy + y * scale

    private fun render(c: Canvas) {
        c.drawColor(Color.rgb(17, 46, 22))
        c.save()
        if (game.shake > 0f) c.translate((rnd.nextFloat() - 0.5f) * game.shake * scale, (rnd.nextFloat() - 0.5f) * game.shake * scale)
        drawField(c)
        drawBallShadow(c)
        val sorted = game.players.sortedBy { it.y }
        for (p in sorted) drawPlayerBody(c, p)
        drawBall(c)
        for (p in sorted) drawPlayerOverlay(c, p)
        drawTexts(c)
        c.restore()
        if (game.onTitle) drawTitle(c) else {
            drawHud(c)
            drawControls(c)
            drawStateBanner(c)
        }
    }

    private fun drawField(c: Canvas) {
        val stripes = 10
        val sw = Cfg.W / stripes
        paint.style = Paint.Style.FILL
        for (i in 0 until stripes) {
            paint.color = if (i % 2 == 0) Color.rgb(46, 125, 50) else Color.rgb(56, 142, 60)
            c.drawRect(sx(i * sw), sy(0f), sx((i + 1) * sw), sy(Cfg.H), paint)
        }
        // Porterías
        val gTop = Cfg.H / 2 - Cfg.GOAL_HALF
        val gBot = Cfg.H / 2 + Cfg.GOAL_HALF
        for (side in 0..1) {
            val x0 = if (side == 0) -30f else Cfg.W
            val x1 = if (side == 0) 0f else Cfg.W + 30f
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(140, 255, 255, 255)
            c.drawRect(sx(x0), sy(gTop), sx(x1), sy(gBot), paint)
            paint.color = Color.argb(120, 80, 80, 80)
            paint.strokeWidth = 1f
            var yy = gTop
            while (yy <= gBot) { c.drawLine(sx(x0), sy(yy), sx(x1), sy(yy), paint); yy += 10f }
            var xx = x0
            while (xx <= x1) { c.drawLine(sx(xx), sy(gTop), sx(xx), sy(gBot), paint); xx += 10f }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * scale
            paint.color = Color.WHITE
            c.drawRect(sx(x0), sy(gTop), sx(x1), sy(gBot), paint)
        }
        // Líneas
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * scale
        paint.color = Color.argb(220, 255, 255, 255)
        c.drawRect(sx(0f), sy(0f), sx(Cfg.W), sy(Cfg.H), paint)
        c.drawLine(sx(Cfg.W / 2), sy(0f), sx(Cfg.W / 2), sy(Cfg.H), paint)
        c.drawCircle(sx(Cfg.W / 2), sy(Cfg.H / 2), 80f * scale, paint)
        c.drawRect(sx(0f), sy(Cfg.H / 2 - 170f), sx(150f), sy(Cfg.H / 2 + 170f), paint)
        c.drawRect(sx(Cfg.W - 150f), sy(Cfg.H / 2 - 170f), sx(Cfg.W), sy(Cfg.H / 2 + 170f), paint)
        c.drawRect(sx(0f), sy(Cfg.H / 2 - 100f), sx(55f), sy(Cfg.H / 2 + 100f), paint)
        c.drawRect(sx(Cfg.W - 55f), sy(Cfg.H / 2 - 100f), sx(Cfg.W), sy(Cfg.H / 2 + 100f), paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(sx(Cfg.W / 2), sy(Cfg.H / 2), 4f * scale, paint)
        // Cuerdas de ring en las bandas y postes en las esquinas
        val ropeColors = intArrayOf(Color.rgb(239, 83, 80), Color.WHITE, Color.rgb(66, 165, 245))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * scale
        for ((i, col) in ropeColors.withIndex()) {
            paint.color = col
            val off = 8f + i * 6f
            c.drawLine(sx(0f), sy(-off), sx(Cfg.W), sy(-off), paint)
            c.drawLine(sx(0f), sy(Cfg.H + off), sx(Cfg.W), sy(Cfg.H + off), paint)
        }
        paint.style = Paint.Style.FILL
        val postR = 11f * scale
        for ((px, py, col) in listOf(
            Triple(0f, -16f, teamColor[0]), Triple(0f, Cfg.H + 16f, teamColor[0]),
            Triple(Cfg.W, -16f, teamColor[1]), Triple(Cfg.W, Cfg.H + 16f, teamColor[1]),
        )) {
            paint.color = Color.rgb(40, 40, 40)
            c.drawCircle(sx(px), sy(py), postR + 2f * scale, paint)
            paint.color = col
            c.drawCircle(sx(px), sy(py), postR, paint)
        }
    }

    private fun drawBallShadow(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 0, 0, 0)
        val b = game.ball
        rect.set(sx(b.x - 6f), sy(b.y + 2f), sx(b.x + 9f), sy(b.y + 9f))
        c.drawOval(rect, paint)
    }

    private fun drawBall(c: Canvas) {
        val b = game.ball
        val r = Cfg.BALL_R * scale
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawCircle(sx(b.x), sy(b.y), r, paint)
        paint.color = Color.rgb(33, 33, 33)
        for (k in 0 until 3) {
            val a = b.spin + k * 2.094f
            c.drawCircle(sx(b.x) + cos(a) * r * 0.5f, sy(b.y) + sin(a) * r * 0.5f, r * 0.28f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * scale
        c.drawCircle(sx(b.x), sy(b.y), r, paint)
    }

    private fun drawPlayerBody(c: Canvas, p: Player) {
        val x = sx(p.x)
        val y = sy(p.y)
        val r = Cfg.PLAYER_R * scale
        paint.style = Paint.Style.FILL
        // Sombra
        paint.color = Color.argb(80, 0, 0, 0)
        rect.set(x - r, y + r * 0.2f, x + r * 1.2f, y + r * 1.1f)
        c.drawOval(rect, paint)
        // Anillo del jugador controlado
        if (p === game.controlled) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * scale
            paint.color = Color.YELLOW
            c.drawCircle(x, y, r * 1.45f, paint)
            paint.style = Paint.Style.FILL
        }
        val shirt = if (p.role == Role.GK) keeperColor[p.team] else teamColor[p.team]
        if (p.isDown) {
            // Tumbado en el suelo
            paint.color = darken(shirt)
            rect.set(x - r * 1.3f, y - r * 0.6f, x + r * 1.3f, y + r * 0.6f)
            c.drawOval(rect, paint)
            paint.color = Color.rgb(255, 204, 128)
            c.drawCircle(x + r * 1.1f, y, r * 0.45f, paint)
            return
        }
        val wob = if (p.stun > 0f) sin(p.stun * 60f) * 2f * scale else 0f
        val cx = x + wob
        // Guantes
        val perpX = -p.fy
        val perpY = p.fx
        for (hand in 0..1) {
            val side = if (hand == 0) -1f else 1f
            var reach = r * 0.75f
            if (p.punchAnim > 0f && p.punchHand == hand) {
                val t = p.punchAnim / 0.18f
                reach += (Cfg.PUNCH_RANGE - 6f) * scale * sin(t * Math.PI.toFloat())
            }
            val gx = cx + p.fx * reach + perpX * side * r * 0.75f
            val gy = y + p.fy * reach + perpY * side * r * 0.75f
            paint.color = Color.rgb(30, 30, 30)
            c.drawCircle(gx, gy, r * 0.52f, paint)
            paint.color = if (p.team == 0) Color.rgb(198, 40, 40) else Color.rgb(250, 250, 250)
            c.drawCircle(gx, gy, r * 0.44f, paint)
        }
        // Cuerpo
        paint.color = teamDark[p.team]
        c.drawCircle(cx, y, r + 1.5f * scale, paint)
        paint.color = if (p.hitFlash > 0f) Color.WHITE else shirt
        c.drawCircle(cx, y, r, paint)
        // Cabeza (mirando hacia donde va)
        paint.color = Color.rgb(255, 204, 128)
        c.drawCircle(cx + p.fx * r * 0.25f, y + p.fy * r * 0.25f, r * 0.5f, paint)
        text.color = Color.argb(200, 0, 0, 0)
        text.textSize = r * 0.75f
        c.drawText(p.number.toString(), cx + p.fx * r * 0.25f, y + p.fy * r * 0.25f + r * 0.27f, text)
    }

    private fun drawPlayerOverlay(c: Canvas, p: Player) {
        val x = sx(p.x)
        val y = sy(p.y)
        val r = Cfg.PLAYER_R * scale
        if (p.isDown) {
            // Estrellitas de K.O.
            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            val t = p.ko * 5f
            for (k in 0 until 3) {
                val a = t + k * 2.094f
                drawStar(c, x + cos(a) * r * 1.2f, y - r * 1.2f + sin(a) * r * 0.4f, r * 0.35f)
            }
            text.textSize = r * 0.9f
            text.color = Color.YELLOW
            c.drawText(String.format("%.0f", kotlin.math.ceil(p.ko.toDouble())), x, y - r * 2.1f, text)
            return
        }
        // Barra de vida
        val bw = r * 2.2f
        val bh = 3.5f * scale
        val by = y - r * 1.9f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(160, 0, 0, 0)
        c.drawRect(x - bw / 2 - 1, by - 1, x + bw / 2 + 1, by + bh + 1, paint)
        val frac = p.hp / 100f
        paint.color = when {
            frac > 0.6f -> Color.rgb(102, 187, 106)
            frac > 0.3f -> Color.rgb(255, 202, 40)
            else -> Color.rgb(239, 83, 80)
        }
        c.drawRect(x - bw / 2, by, x - bw / 2 + bw * frac, by + bh, paint)
        if (p === game.controlled) {
            paint.color = Color.YELLOW
            val ty = by - 6f * scale
            val path = android.graphics.Path()
            path.moveTo(x - 6f * scale, ty - 8f * scale)
            path.lineTo(x + 6f * scale, ty - 8f * scale)
            path.lineTo(x, ty)
            path.close()
            c.drawPath(path, paint)
        }
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float) {
        val path = android.graphics.Path()
        for (i in 0 until 10) {
            val a = -Math.PI.toFloat() / 2 + i * Math.PI.toFloat() / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawTexts(c: Canvas) {
        for (t in game.texts) {
            val a = (255 * min(1f, t.life / t.maxLife * 2f)).toInt().coerceIn(0, 255)
            text.textSize = (if (t.big) 46f else 22f) * scale
            text.style = Paint.Style.STROKE
            text.strokeWidth = 4f * scale
            text.color = Color.argb(a, 0, 0, 0)
            c.drawText(t.text, sx(t.x), sy(t.y), text)
            text.style = Paint.Style.FILL
            text.color = (t.color and 0x00FFFFFF) or (a shl 24)
            c.drawText(t.text, sx(t.x), sy(t.y), text)
        }
    }

    private fun drawHud(c: Canvas) {
        val hud = viewH * 0.11f
        val cx = viewW / 2
        val bh = hud * 0.78f
        val top = hud * 0.12f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 20, 20, 20)
        rect.set(cx - bh * 4.2f, top, cx + bh * 4.2f, top + bh)
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, paint)
        paint.color = teamColor[0]
        rect.set(cx - bh * 4.2f, top, cx - bh * 2.2f, top + bh)
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, paint)
        paint.color = teamColor[1]
        rect.set(cx + bh * 2.2f, top, cx + bh * 4.2f, top + bh)
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, paint)

        text.style = Paint.Style.FILL
        text.color = Color.WHITE
        text.textSize = bh * 0.42f
        c.drawText("TÚ", cx - bh * 3.2f, top + bh * 0.48f, text)
        c.drawText("CPU", cx + bh * 3.2f, top + bh * 0.48f, text)
        text.textSize = bh * 0.3f
        c.drawText("K.O. ${game.kos[0]}", cx - bh * 3.2f, top + bh * 0.85f, text)
        c.drawText("K.O. ${game.kos[1]}", cx + bh * 3.2f, top + bh * 0.85f, text)

        text.textSize = bh * 0.62f
        c.drawText("${game.score[0]}  -  ${game.score[1]}", cx - bh * 0.6f, top + bh * 0.7f, text)
        val t = kotlin.math.ceil(game.time.toDouble()).toInt()
        text.textSize = bh * 0.45f
        text.color = if (game.time < 30f) Color.rgb(255, 138, 128) else Color.rgb(255, 241, 118)
        c.drawText(String.format("%d:%02d", t / 60, t % 60), cx + bh * 1.35f, top + bh * 0.65f, text)
    }

    private fun drawControls(c: Canvas) {
        paint.style = Paint.Style.FILL
        // Joystick
        if (joyId != -1) {
            paint.color = Color.argb(60, 255, 255, 255)
            c.drawCircle(joyCx, joyCy, joyR, paint)
            paint.color = Color.argb(150, 255, 255, 255)
            c.drawCircle(joyCx + game.input.mx * joyR, joyCy + game.input.my * joyR, joyR * 0.42f, paint)
        } else {
            val hx = viewH * 0.2f
            val hy = viewH - viewH * 0.2f
            paint.color = Color.argb(35, 255, 255, 255)
            c.drawCircle(hx, hy, joyR, paint)
            paint.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(hx, hy, joyR * 0.42f, paint)
        }
        // Botones
        val hasBall = game.ball.owner === game.controlled && game.controlled != null
        for (b in buttons) {
            val pressed = b.pointer != -1
            val dim = (b === btnShoot && !hasBall)
            paint.color = Color.argb(if (pressed) 230 else if (dim) 70 else 150, Color.red(b.color), Color.green(b.color), Color.blue(b.color))
            c.drawCircle(b.x, b.y, b.r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = b.r * 0.08f
            paint.color = Color.argb(if (dim) 80 else 200, 255, 255, 255)
            c.drawCircle(b.x, b.y, b.r, paint)
            paint.style = Paint.Style.FILL
            text.color = Color.argb(if (dim) 110 else 255, 255, 255, 255)
            val label = if (b === btnPass && !hasBall) "CAMBIO" else b.label
            text.textSize = b.r * (if (label.length > 5) 0.32f else 0.4f)
            c.drawText(label, b.x, b.y + text.textSize * 0.35f, text)
        }
    }

    private fun drawStateBanner(c: Canvas) {
        when (game.state) {
            State.GOAL -> {
                val col = teamColor[game.lastScorerTeam]
                banner(c, "¡GOOOL!", col, scorerLine())
            }
            State.END -> {
                val (msg, col) = when {
                    game.score[0] > game.score[1] -> "¡VICTORIA!" to teamColor[0]
                    game.score[0] < game.score[1] -> "DERROTA" to teamColor[1]
                    else -> "EMPATE" to Color.rgb(120, 120, 120)
                }
                banner(c, msg, col, "${game.score[0]} - ${game.score[1]}   ·   K.O.: ${game.kos[0]} - ${game.kos[1]}")
                if (game.stateTimer <= 0f) {
                    text.textSize = viewH * 0.045f
                    text.color = Color.WHITE
                    c.drawText("Toca para jugar otra vez", viewW / 2, viewH * 0.72f, text)
                }
            }
            else -> { }
        }
    }

    private fun scorerLine(): String {
        val who = if (game.lastScorerTeam == 0) "TU EQUIPO" else "LA CPU"
        val s = game.lastScorer ?: return "Marca $who"
        return if (s.team != game.lastScorerTeam) "¡En propia puerta! Punto para $who"
        else "Gol del dorsal ${s.number} de ${if (s.team == 0) "tu equipo" else "la CPU"}"
    }

    private fun banner(c: Canvas, title: String, color: Int, sub: String) {
        val h = viewH * 0.24f
        val top = viewH * 0.36f
        paint.style = Paint.Style.FILL
        paint.color = (color and 0x00FFFFFF) or (0xDD shl 24)
        c.drawRect(0f, top, viewW, top + h, paint)
        text.style = Paint.Style.FILL
        text.color = Color.WHITE
        text.textSize = h * 0.48f
        c.drawText(title, viewW / 2, top + h * 0.55f, text)
        text.textSize = h * 0.16f
        c.drawText(sub, viewW / 2, top + h * 0.85f, text)
    }

    private fun drawTitle(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, viewW, viewH, paint)
        val cx = viewW / 2
        text.style = Paint.Style.FILL
        text.textSize = viewH * 0.17f
        text.color = Color.rgb(255, 235, 59)
        c.drawText("FUTBOXEO", cx, viewH * 0.27f, text)
        text.textSize = viewH * 0.05f
        text.color = Color.WHITE
        c.drawText("Fútbol + Boxeo · 4 contra 4 · 3 minutos", cx, viewH * 0.37f, text)

        text.textSize = viewH * 0.038f
        text.color = Color.rgb(224, 224, 224)
        val lines = listOf(
            "Izquierda: desliza el dedo para moverte",
            "PUÑO: pega al rival que tengas delante — si tiene el balón, lo suelta",
            "Si a alguien se le acaba la vida: ¡K.O.! y se queda 3 segundos en el suelo",
            "ESQUIVA: un impulso rápido que te libra de los golpes",
            "PASE / TIRO: con balón · CAMBIO: cambia de jugador sin balón",
        )
        for ((i, l) in lines.withIndex()) c.drawText(l, cx, viewH * (0.5f + i * 0.065f), text)

        val pulse = 0.5f + 0.5f * sin(System.nanoTime() / 2.5e8f)
        text.textSize = viewH * 0.06f
        text.color = Color.argb((140 + 115 * pulse).toInt(), 255, 255, 255)
        c.drawText("TOCA PARA EMPEZAR", cx, viewH * 0.9f, text)
    }

    private fun darken(c: Int) = Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10)
}
