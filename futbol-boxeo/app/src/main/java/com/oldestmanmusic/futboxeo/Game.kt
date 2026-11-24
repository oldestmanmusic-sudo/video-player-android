package com.oldestmanmusic.futboxeo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Medidas del campo y ajustes del juego (en unidades del mundo, no en píxeles). */
object Cfg {
    const val W = 1000f
    const val H = 600f
    const val GOAL_HALF = 80f
    const val PLAYER_R = 15f
    const val BALL_R = 7f
    const val SPEED = 175f
    const val SPEED_WITH_BALL = 150f
    const val CPU_SPEED_FACTOR = 0.93f
    const val DASH_SPEED = 480f
    const val PUNCH_RANGE = 44f
    const val SHOT_POWER = 640f
    const val MATCH_TIME = 180f
    const val KO_TIME = 3f
}

enum class Role { GK, DEF, LW, RW }

enum class State { KICKOFF, PLAY, GOAL, END }

/** Sucesos que la vista convierte en vibración y sonido. */
enum class GameEvent { HIT, KO, GOAL_HUMAN, GOAL_CPU, WHISTLE, KICK }

class Player(val team: Int, val role: Role, val number: Int, val homeX: Float, val homeY: Float) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var fx = if (team == 0) 1f else -1f
    var fy = 0f
    var wantX = 0f
    var wantY = 0f
    var hp = 100f
    var ko = 0f
    var stun = 0f
    var punchCd = 0f
    var punchAnim = 0f
    var punchHand = 0
    var dash = 0f
    var dashCd = 0f
    var kickCd = 0f
    var think = 0f
    var holdT = 0f
    var lane = 0f
    var hitFlash = 0f

    val isDown get() = ko > 0f
    val canAct get() = ko <= 0f && stun <= 0f
    val attackDir get() = if (team == 0) 1f else -1f
}

class Ball {
    var x = Cfg.W / 2
    var y = Cfg.H / 2
    var vx = 0f
    var vy = 0f
    var spin = 0f
    var owner: Player? = null
    var lastTouch: Player? = null
}

class FloatText(var x: Float, var y: Float, val text: String, val color: Int, val big: Boolean) {
    var life = if (big) 1.3f else 0.8f
    val maxLife = life
}

/** Mandos del jugador humano. La vista los escribe y el juego los consume. */
class Input {
    @Volatile var mx = 0f
    @Volatile var my = 0f
    @Volatile var pass = false
    @Volatile var shoot = false
    @Volatile var punch = false
    @Volatile var dodge = false
    @Volatile var tap = false

    fun clearButtons() {
        pass = false; shoot = false; punch = false; dodge = false; tap = false
    }
}

class Game(private val rnd: Random = Random.Default) {
    val players = ArrayList<Player>()
    val ball = Ball()
    val score = IntArray(2)
    val kos = IntArray(2)
    val texts = ArrayList<FloatText>()
    val input = Input()

    var state = State.KICKOFF
        private set
    /** En la portada se juega un partido de demostración (CPU contra CPU) de fondo. */
    var onTitle = true
        private set
    var stateTimer = 0f
        private set
    var time = Cfg.MATCH_TIME
        private set
    var humanPlays = false
        private set
    var controlled: Player? = null
        private set
    var shake = 0f
        private set
    var lastScorer: Player? = null
        private set
    var onEvent: ((GameEvent) -> Unit)? = null

    init {
        buildTeams()
        startMatch(human = false)
    }

    private fun buildTeams() {
        players.clear()
        val layout = listOf(
            Triple(Role.GK, 1, Pair(35f, 300f)),
            Triple(Role.DEF, 4, Pair(230f, 300f)),
            Triple(Role.LW, 7, Pair(410f, 150f)),
            Triple(Role.RW, 9, Pair(410f, 450f)),
        )
        for (team in 0..1) {
            for ((role, num, pos) in layout) {
                val hx = if (team == 0) pos.first else Cfg.W - pos.first
                val hy = if (team == 0) pos.second else Cfg.H - pos.second
                players += Player(team, role, num, hx, hy)
            }
        }
    }

    fun startMatch(human: Boolean) {
        humanPlays = human
        score[0] = 0; score[1] = 0
        kos[0] = 0; kos[1] = 0
        time = Cfg.MATCH_TIME
        texts.clear()
        for (p in players) {
            p.hp = 100f; p.ko = 0f; p.stun = 0f
        }
        kickoff(if (rnd.nextBoolean()) 0 else 1)
    }

    private fun kickoff(team: Int) {
        for (p in players) {
            p.x = p.homeX; p.y = p.homeY
            p.vx = 0f; p.vy = 0f
            p.fx = p.attackDir; p.fy = 0f
            p.stun = 0f; p.dash = 0f; p.punchAnim = 0f; p.kickCd = 0f; p.holdT = 0f
            if (p.ko > 0f) { p.ko = 0f; p.hp = 100f }
        }
        val taker = players.first { it.team == team && it.role == Role.LW }
        taker.x = Cfg.W / 2 - taker.attackDir * (Cfg.PLAYER_R + Cfg.BALL_R + 4f)
        taker.y = Cfg.H / 2
        ball.x = Cfg.W / 2; ball.y = Cfg.H / 2
        ball.vx = 0f; ball.vy = 0f
        ball.owner = null
        ball.lastTouch = null
        controlled = if (humanPlays) players.first { it.team == 0 && it.role == Role.LW } else null
        state = State.KICKOFF
        stateTimer = 1.3f
        if (humanPlays) emit(GameEvent.WHISTLE)
    }

    // ------------------------------------------------------------------ bucle

    fun update(dt: Float) {
        shake = max(0f, shake - dt * 30f)
        updateTexts(dt)
        if (onTitle && input.tap) {
            input.clearButtons()
            onTitle = false
            startMatch(human = true)
            return
        }
        when (state) {
            State.KICKOFF -> {
                stateTimer -= dt
                input.clearButtons()
                if (stateTimer <= 0f) {
                    state = State.PLAY
                    if (humanPlays) addText(Cfg.W / 2, Cfg.H / 2 - 60f, "¡A JUGAR!", COLOR_WHITE, true)
                }
            }
            State.PLAY -> {
                time -= dt
                simulate(dt)
                if (time <= 0f && state == State.PLAY) {
                    time = 0f
                    state = State.END
                    stateTimer = 1.5f
                    emit(GameEvent.WHISTLE)
                }
            }
            State.GOAL -> {
                stateTimer -= dt
                settle(dt)
                if (stateTimer <= 0f) kickoff(if (lastScorerTeam == 0) 1 else 0)
            }
            State.END -> {
                stateTimer -= dt
                settle(dt)
                if (onTitle) {
                    startMatch(human = false)
                } else if (stateTimer <= 0f && input.tap) {
                    input.clearButtons()
                    startMatch(human = true)
                }
            }
        }
    }

    /** Tras un gol o al final: todos frenan y el balón sigue rodando. */
    private fun settle(dt: Float) {
        for (p in players) {
            tickTimers(p, dt)
            p.wantX = 0f; p.wantY = 0f
            physics(p, dt)
        }
        separatePlayers()
        moveBall(dt)
        if (state != State.END || stateTimer > 0f) input.clearButtons()
    }

    var lastScorerTeam = 0
        private set

    private fun simulate(dt: Float) {
        for (p in players) tickTimers(p, dt)
        val c = controlled
        if (humanPlays && c != null && (c.isDown)) switchToBestPlayer()
        for (p in players) {
            if (p === controlled) humanControl(p) else ai(p, dt)
        }
        for (p in players) physics(p, dt)
        separatePlayers()
        moveBall(dt)
        if (state == State.PLAY) checkPickup()
    }

    private fun tickTimers(p: Player, dt: Float) {
        if (p.ko > 0f) {
            p.ko -= dt
            if (p.ko <= 0f) { p.ko = 0f; p.hp = 100f; p.stun = 0.3f }
        } else {
            p.hp = min(100f, p.hp + 5f * dt)
        }
        p.stun = max(0f, p.stun - dt)
        p.punchCd = max(0f, p.punchCd - dt)
        p.punchAnim = max(0f, p.punchAnim - dt)
        p.dash = max(0f, p.dash - dt)
        p.dashCd = max(0f, p.dashCd - dt)
        p.kickCd = max(0f, p.kickCd - dt)
        p.think -= dt
        p.hitFlash = max(0f, p.hitFlash - dt)
        if (ball.owner === p) p.holdT += dt else p.holdT = 0f
    }

    private fun updateTexts(dt: Float) {
        val it = texts.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.life -= dt
            t.y -= dt * (if (t.big) 15f else 40f)
            if (t.life <= 0f) it.remove()
        }
    }

    // ------------------------------------------------------------- humano

    private fun humanControl(p: Player) {
        p.wantX = input.mx
        p.wantY = input.my
        if (!p.canAct) { input.clearButtons(); return }
        if (input.punch) { input.punch = false; punch(p) }
        if (input.dodge) { input.dodge = false; dodge(p) }
        if (input.shoot) {
            input.shoot = false
            if (ball.owner === p) shoot(p, aimY = input.my)
        }
        if (input.pass) {
            input.pass = false
            if (ball.owner === p) pass(p) else switchToBestPlayer()
        }
        input.tap = false
    }

    private fun switchToBestPlayer() {
        val best = players.filter { it.team == 0 && it.role != Role.GK && !it.isDown && it !== controlled }
            .minByOrNull { dist(it.x, it.y, ball.x, ball.y) } ?: return
        val cur = controlled
        if (cur == null || cur.isDown || dist(best.x, best.y, ball.x, ball.y) < dist(cur.x, cur.y, ball.x, ball.y) + 40f) {
            cur?.let { it.wantX = 0f; it.wantY = 0f }
            controlled = best
        }
    }

    // ------------------------------------------------------------ acciones

    private fun punch(p: Player) {
        if (!p.canAct || p.punchCd > 0f) return
        p.punchCd = 0.42f
        p.punchAnim = 0.18f
        p.punchHand = 1 - p.punchHand
        var target: Player? = null
        var best = Float.MAX_VALUE
        for (o in players) {
            if (o.team == p.team || o.isDown) continue
            val dx = o.x - p.x
            val dy = o.y - p.y
            val d = hypot(dx, dy)
            if (d > Cfg.PUNCH_RANGE + Cfg.PLAYER_R || d < 0.01f) continue
            if ((dx * p.fx + dy * p.fy) / d < 0.3f) continue
            if (d < best) { best = d; target = o }
        }
        val t = target ?: return
        if (t.dash > 0f) {
            addText(t.x, t.y - 30f, "¡ESQUIVA!", COLOR_CYAN, false)
            return
        }
        val dx = (t.x - p.x) / best
        val dy = (t.y - p.y) / best
        val dmg = 17f + rnd.nextFloat() * 10f
        t.hp -= dmg
        t.vx += dx * 300f
        t.vy += dy * 300f
        t.stun = 0.4f
        t.hitFlash = 0.15f
        t.punchAnim = 0f
        if (ball.owner === t) {
            ball.owner = null
            ball.vx = dx * 140f + (rnd.nextFloat() - 0.5f) * 120f
            ball.vy = dy * 140f + (rnd.nextFloat() - 0.5f) * 120f
            t.kickCd = 0.6f
        }
        if (t.hp <= 0f) {
            t.hp = 0f
            t.ko = Cfg.KO_TIME
            t.stun = 0f
            t.vx += dx * 200f
            t.vy += dy * 200f
            kos[p.team]++
            shake = 14f
            addText(t.x, t.y - 40f, "¡K.O.!", COLOR_YELLOW, true)
            emit(GameEvent.KO)
        } else {
            addText(t.x, t.y - 30f, HIT_WORDS[rnd.nextInt(HIT_WORDS.size)], COLOR_WHITE, false)
            shake = max(shake, 5f)
            emit(GameEvent.HIT)
        }
    }

    private fun dodge(p: Player) {
        if (!p.canAct || p.dashCd > 0f) return
        var dx = p.wantX
        var dy = p.wantY
        val m = hypot(dx, dy)
        if (m < 0.2f) { dx = p.fx; dy = p.fy } else { dx /= m; dy /= m }
        p.dash = 0.2f
        p.dashCd = 0.9f
        p.vx = dx * Cfg.DASH_SPEED
        p.vy = dy * Cfg.DASH_SPEED
    }

    private fun kick(p: Player, dx: Float, dy: Float, power: Float) {
        val m = hypot(dx, dy).takeIf { it > 0.001f } ?: return
        ball.owner = null
        ball.vx = dx / m * power
        ball.vy = dy / m * power
        ball.lastTouch = p
        p.kickCd = 0.3f
        emit(GameEvent.KICK)
    }

    private fun shoot(p: Player, aimY: Float) {
        val goalX = if (p.team == 0) Cfg.W + 20f else -20f
        val toGoalX = goalX - p.x
        val facingGoal = p.fx * p.attackDir > -0.2f
        if (facingGoal) {
            val targetY = Cfg.H / 2 + aimY * Cfg.GOAL_HALF * 0.85f + (rnd.nextFloat() - 0.5f) * 50f
            kick(p, toGoalX, targetY - p.y, Cfg.SHOT_POWER)
        } else {
            kick(p, p.fx, p.fy, Cfg.SHOT_POWER * 0.9f)
        }
    }

    private fun pass(p: Player) {
        val mates = players.filter { it.team == p.team && it !== p && !it.isDown && it.role != Role.GK }
        var best: Player? = null
        var bestScore = -Float.MAX_VALUE
        for (m in mates) {
            val dx = m.x - p.x
            val dy = m.y - p.y
            val d = max(1f, hypot(dx, dy))
            val align = (dx * p.fx + dy * p.fy) / d
            val forward = dx * p.attackDir
            var s = align * 2.2f + forward / 300f - d / 700f
            // Penaliza pases con un rival justo en la línea.
            for (o in players) {
                if (o.team == p.team || o.isDown) continue
                if (distToSegment(o.x, o.y, p.x, p.y, m.x, m.y) < 22f) s -= 1.2f
            }
            if (s > bestScore) { bestScore = s; best = m }
        }
        val r = best ?: run { kick(p, p.fx, p.fy, 400f); return }
        val tx = r.x + r.vx * 0.35f
        val ty = r.y + r.vy * 0.35f
        val d = dist(p.x, p.y, tx, ty)
        kick(p, tx - p.x, ty - p.y, (d * 1.5f + 150f).coerceIn(260f, 560f))
        if (p === controlled) controlled = r
    }

    // -------------------------------------------------------------- física

    private fun physics(p: Player, dt: Float) {
        if (p.dash > 0f) {
            // Se mantiene la velocidad del regate.
        } else {
            var wx = p.wantX
            var wy = p.wantY
            val m = hypot(wx, wy)
            if (m > 1f) { wx /= m; wy /= m }
            if (!p.canAct) { wx = 0f; wy = 0f }
            var speed = if (ball.owner === p) Cfg.SPEED_WITH_BALL else Cfg.SPEED
            if (p.team == 1 || !humanPlays) speed *= Cfg.CPU_SPEED_FACTOR
            if (p.punchAnim > 0f) speed *= 0.6f
            val k = min(1f, (if (p.isDown) 4f else 10f) * dt)
            p.vx += (wx * speed - p.vx) * k
            p.vy += (wy * speed - p.vy) * k
            if (m > 0.15f && p.canAct) { p.fx = wx; p.fy = wy; normFacing(p) }
        }
        p.x += p.vx * dt
        p.y += p.vy * dt
        val r = Cfg.PLAYER_R
        if (p.x < r) { p.x = r; p.vx = 0f }
        if (p.x > Cfg.W - r) { p.x = Cfg.W - r; p.vx = 0f }
        if (p.y < r) { p.y = r; p.vy = 0f }
        if (p.y > Cfg.H - r) { p.y = Cfg.H - r; p.vy = 0f }
    }

    private fun normFacing(p: Player) {
        val m = hypot(p.fx, p.fy)
        if (m > 0.001f) { p.fx /= m; p.fy /= m } else { p.fx = p.attackDir; p.fy = 0f }
    }

    private fun separatePlayers() {
        val minD = Cfg.PLAYER_R * 2f
        for (i in players.indices) for (j in i + 1 until players.size) {
            val a = players[i]
            val b = players[j]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = hypot(dx, dy)
            if (d < minD && d > 0.001f) {
                val push = (minD - d) / 2f
                val nx = dx / d
                val ny = dy / d
                a.x -= nx * push; a.y -= ny * push
                b.x += nx * push; b.y += ny * push
            } else if (d <= 0.001f) {
                a.x -= 1f; b.x += 1f
            }
        }
    }

    private fun moveBall(dt: Float) {
        val o = ball.owner
        if (o != null) {
            if (!o.canAct) {
                ball.owner = null
            } else {
                val off = Cfg.PLAYER_R + Cfg.BALL_R + 2f
                var fx = o.fx
                var fy = o.fy
                if (o.role == Role.GK) { fx = o.attackDir; fy = 0f }
                ball.x = o.x + fx * off
                ball.y = o.y + fy * off
                ball.vx = o.vx; ball.vy = o.vy
                ball.spin += hypot(o.vx, o.vy) * dt * 0.1f
                ball.lastTouch = o
                // No se puede meter un gol en propia puerta conduciendo.
                val ownLineX = if (o.team == 0) Cfg.BALL_R else Cfg.W - Cfg.BALL_R
                if (o.team == 0 && ball.x < ownLineX) ball.x = ownLineX
                if (o.team == 1 && ball.x > ownLineX) ball.x = ownLineX
                ball.y = ball.y.coerceIn(Cfg.BALL_R, Cfg.H - Cfg.BALL_R)
                checkGoal()
                return
            }
        }
        ball.x += ball.vx * dt
        ball.y += ball.vy * dt
        val f = exp(-1.1f * dt)
        ball.vx *= f; ball.vy *= f
        if (hypot(ball.vx, ball.vy) < 4f) { ball.vx = 0f; ball.vy = 0f }
        ball.spin += hypot(ball.vx, ball.vy) * dt * 0.1f
        val r = Cfg.BALL_R
        if (ball.y < r) { ball.y = r; ball.vy = abs(ball.vy) * 0.8f }
        if (ball.y > Cfg.H - r) { ball.y = Cfg.H - r; ball.vy = -abs(ball.vy) * 0.8f }
        val inMouth = abs(ball.y - Cfg.H / 2) < Cfg.GOAL_HALF - r * 0.5f
        if (!inMouth || state != State.PLAY) {
            if (ball.x < r) { ball.x = r; ball.vx = abs(ball.vx) * 0.8f }
            if (ball.x > Cfg.W - r) { ball.x = Cfg.W - r; ball.vx = -abs(ball.vx) * 0.8f }
        } else {
            // Dentro de la red: que no se escape por detrás.
            ball.x = ball.x.coerceIn(-28f, Cfg.W + 28f)
        }
        checkGoal()
    }

    private fun checkGoal() {
        if (state != State.PLAY) return
        if (abs(ball.y - Cfg.H / 2) >= Cfg.GOAL_HALF) return
        val scoringTeam = when {
            ball.x < -Cfg.BALL_R -> 1
            ball.x > Cfg.W + Cfg.BALL_R -> 0
            else -> return
        }
        score[scoringTeam]++
        lastScorerTeam = scoringTeam
        lastScorer = ball.lastTouch
        ball.owner = null
        ball.vx *= 0.2f; ball.vy *= 0.2f
        state = State.GOAL
        stateTimer = 2.4f
        shake = 10f
        emit(if (scoringTeam == 0 && humanPlays) GameEvent.GOAL_HUMAN else GameEvent.GOAL_CPU)
    }

    private fun checkPickup() {
        if (ball.owner != null) return
        val speed = hypot(ball.vx, ball.vy)
        var best: Player? = null
        var bestD = Float.MAX_VALUE
        for (p in players) {
            if (!p.canAct || p.kickCd > 0f || p.dash > 0f) continue
            val reach = Cfg.PLAYER_R + Cfg.BALL_R + if (p.role == Role.GK) 10f else 4f
            val d = dist(p.x, p.y, ball.x, ball.y)
            if (d < reach && d < bestD) { best = p; bestD = d }
        }
        val p = best ?: return
        if (speed > 360f && p.role != Role.GK && rnd.nextFloat() > 0.35f) {
            // Balón muy rápido: se le escapa.
            p.kickCd = 0.25f
            return
        }
        ball.owner = p
        ball.lastTouch = p
        p.holdT = 0f
        p.think = 0.15f + rnd.nextFloat() * 0.2f
        p.lane = (rnd.nextFloat() - 0.5f) * 220f
        if (humanPlays && p.team == 0 && p.role != Role.GK) controlled = p
    }

    // ------------------------------------------------------------------- IA

    private fun ai(p: Player, dt: Float) {
        p.wantX = 0f; p.wantY = 0f
        if (!p.canAct) return
        val owner = ball.owner
        val ready = p.think <= 0f
        if (ready) p.think = 0.18f + rnd.nextFloat() * 0.25f

        if (p.role == Role.GK) { aiKeeper(p, owner, ready); return }

        when {
            owner === p -> aiCarrier(p, ready)
            owner != null && owner.team == p.team -> {
                val (tx, ty) = formationTarget(p, attacking = 1)
                moveTo(p, tx, ty, 12f)
            }
            owner != null -> {
                if (isChaser(p, owner.x, owner.y)) {
                    moveTo(p, owner.x + owner.fx * 6f, owner.y + owner.fy * 6f, 0f)
                    if (ready && inPunchRange(p, owner) && rnd.nextFloat() < 0.85f) punch(p)
                } else {
                    val (tx, ty) = formationTarget(p, attacking = -1)
                    moveTo(p, tx, ty, 12f)
                }
            }
            else -> {
                if (isChaser(p, ball.x, ball.y)) {
                    moveTo(p, ball.x + ball.vx * 0.25f, ball.y + ball.vy * 0.25f, 0f)
                } else {
                    val (tx, ty) = formationTarget(p, attacking = 0)
                    moveTo(p, tx, ty, 12f)
                }
            }
        }

        // ¡Esto también es boxeo! Golpes de oportunidad contra quien esté cerca.
        if (ready && ball.owner !== p) {
            val near = players.firstOrNull { it.team != p.team && !it.isDown && inPunchRange(p, it) }
            if (near != null && rnd.nextFloat() < 0.22f) {
                faceTo(p, near.x, near.y)
                punch(p)
            }
        }
    }

    private fun aiKeeper(p: Player, owner: Player?, ready: Boolean) {
        val lineX = if (p.team == 0) 32f else Cfg.W - 32f
        if (owner === p) {
            if (p.holdT > 0.7f) pass(p)
            return
        }
        val ballDistGoal = abs(ball.x - (if (p.team == 0) 0f else Cfg.W))
        if (owner == null && ballDistGoal < 160f && abs(ball.y - Cfg.H / 2) < 170f) {
            moveTo(p, ball.x, ball.y, 0f)
            return
        }
        if (owner != null && owner.team != p.team && ballDistGoal < 120f) {
            moveTo(p, owner.x, owner.y, 0f)
            if (ready && inPunchRange(p, owner)) punch(p)
            return
        }
        // Anticipa la trayectoria del balón hacia su portería.
        var ty = ball.y
        val towardsMe = (ball.vx < 0f && p.team == 0) || (ball.vx > 0f && p.team == 1)
        if (owner == null && towardsMe && abs(ball.vx) > 50f) {
            val t = (lineX - ball.x) / ball.vx
            if (t in 0f..1.5f) ty = ball.y + ball.vy * t
        }
        ty = ty.coerceIn(Cfg.H / 2 - Cfg.GOAL_HALF + 8f, Cfg.H / 2 + Cfg.GOAL_HALF - 8f)
        moveTo(p, lineX, ty, 3f)
        faceTo(p, ball.x, ball.y)
    }

    private fun aiCarrier(p: Player, ready: Boolean) {
        val goalX = if (p.team == 0) Cfg.W else 0f
        val distGoal = abs(goalX - p.x)
        var tx = goalX
        var ty = Cfg.H / 2 + if (distGoal > 250f) p.lane else p.lane * 0.2f
        // Esquiva al rival que tenga delante.
        val threat = players.filter { it.team != p.team && !it.isDown }
            .minByOrNull { dist(it.x, it.y, p.x, p.y) }
        var threatClose = false
        if (threat != null) {
            val dx = threat.x - p.x
            val dy = threat.y - p.y
            val d = hypot(dx, dy)
            val ahead = dx * p.attackDir > -10f
            if (d < 90f && ahead) {
                val side = if (dy > 0) -1f else 1f
                ty = p.y + side * 120f
                tx = p.x + p.attackDir * 60f
            }
            threatClose = d < 55f
        }
        moveTo(p, tx, ty, 0f)
        if (!ready) return
        val r = rnd.nextFloat()
        when {
            distGoal < 170f && r < 0.9f -> { faceTo(p, goalX, Cfg.H / 2); shoot(p, aimY = rnd.nextFloat() * 2f - 1f) }
            distGoal < 290f && r < 0.45f -> { faceTo(p, goalX, Cfg.H / 2); shoot(p, aimY = rnd.nextFloat() * 2f - 1f) }
            threatClose && threat != null -> {
                val r2 = rnd.nextFloat()
                when {
                    r2 < 0.35f -> pass(p)
                    r2 < 0.65f -> { faceTo(p, threat.x, threat.y); punch(p) }
                    r2 < 0.85f -> dodge(p)
                }
            }
            p.holdT > 2.5f && r < 0.3f -> pass(p)
        }
    }

    private fun formationTarget(p: Player, attacking: Int): Pair<Float, Float> {
        val ballRel = if (p.team == 0) ball.x else Cfg.W - ball.x
        val homeRel = if (p.team == 0) p.homeX else Cfg.W - p.homeX
        var rel = homeRel + (ballRel - Cfg.W / 2) * 0.55f + attacking * 90f
        rel = when (p.role) {
            Role.DEF -> rel.coerceIn(110f, 560f)
            else -> rel.coerceIn(200f, 880f)
        }
        val x = if (p.team == 0) rel else Cfg.W - rel
        val y = (p.homeY + (ball.y - Cfg.H / 2) * 0.3f).coerceIn(40f, Cfg.H - 40f)
        return Pair(x, y)
    }

    /** ¿Es este jugador el de su equipo más cercano a (x, y)? (sin contar al portero) */
    private fun isChaser(p: Player, x: Float, y: Float): Boolean {
        val myD = dist(p.x, p.y, x, y)
        for (o in players) {
            if (o === p || o.team != p.team || o.role == Role.GK || o.isDown) continue
            if (dist(o.x, o.y, x, y) < myD) return false
        }
        return true
    }

    private fun inPunchRange(p: Player, o: Player): Boolean {
        val dx = o.x - p.x
        val dy = o.y - p.y
        val d = hypot(dx, dy)
        return d < Cfg.PUNCH_RANGE + Cfg.PLAYER_R - 2f && d > 0.01f && (dx * p.fx + dy * p.fy) / d > 0.35f
    }

    private fun moveTo(p: Player, tx: Float, ty: Float, deadZone: Float) {
        val dx = tx - p.x
        val dy = ty - p.y
        val d = hypot(dx, dy)
        if (d <= deadZone || d < 0.5f) return
        val s = min(1f, d / 30f)
        p.wantX = dx / d * s
        p.wantY = dy / d * s
    }

    private fun faceTo(p: Player, tx: Float, ty: Float) {
        val dx = tx - p.x
        val dy = ty - p.y
        val d = hypot(dx, dy)
        if (d > 0.01f) { p.fx = dx / d; p.fy = dy / d }
    }

    // ------------------------------------------------------------- utilidades

    private fun addText(x: Float, y: Float, s: String, color: Int, big: Boolean) {
        if (texts.size > 20) texts.removeAt(0)
        texts += FloatText(x, y, s, color, big)
    }

    private fun emit(e: GameEvent) = onEvent?.invoke(e)

    companion object {
        const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        const val COLOR_YELLOW = 0xFFFFEB3B.toInt()
        const val COLOR_CYAN = 0xFF80DEEA.toInt()
        val HIT_WORDS = arrayOf("¡POW!", "¡ZAS!", "¡PAM!", "¡BAM!", "¡TOMA!")

        fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)

        fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            if (l2 < 0.001f) return dist(px, py, ax, ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
            return dist(px, py, ax + t * dx, ay + t * dy)
        }
    }
}
