package com.ox.zombieshooter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Twin-stick 2D zombie shooter.
 *  - Left half of the screen: floating joystick to move.
 *  - Right half of the screen: floating joystick to aim; holding it fires.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { MENU, PLAYING, PAUSED, GAME_OVER }

    private class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float)

    private class Zombie(
        var x: Float,
        var y: Float,
        var hp: Float,
        val speed: Float,
        val radius: Float,
        val type: Int,
        val damage: Float
    ) {
        var flash = 0f
        var wobble = Random.nextFloat() * 6f
    }

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
        val color: Int,
        val size: Float
    )

    private class Pickup(val x: Float, val y: Float, var life: Float)

    // ---- palette (matches the app icon) ----
    private val cBg = Color.rgb(7, 18, 14)
    private val cNeon = Color.rgb(32, 227, 160)
    private val cBlue = Color.rgb(64, 156, 255)
    private val cRed = Color.rgb(255, 72, 72)
    private val cWhite = Color.rgb(235, 245, 240)

    // ---- engine ----
    private val density = resources.displayMetrics.density
    private val lock = Any()
    private val prefs = context.getSharedPreferences("ox_zombie", Context.MODE_PRIVATE)
    private var thread: Thread? = null
    @Volatile private var running = false
    private var ready = false
    private var w = 0
    private var h = 0
    private var bg: Bitmap? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // ---- game state ----
    private var state = State.MENU
    private var stateTime = 0f
    private var time = 0f
    private var best = prefs.getInt("best", 0)

    private val pr = 14f * density            // player radius
    private val stickR = 56f * density        // joystick radius
    private val maxHp = 100f

    private var px = 0f
    private var py = 0f
    private var hp = maxHp
    private var angle = 0f
    private var cooldown = 0f
    private var hurt = 0f
    private var shake = 0f
    private var walkCycle = 0f

    private var score = 0
    private var kills = 0
    private var wave = 0
    private var toSpawn = 0
    private var spawnTimer = 0f
    private var waveBreak = 0f
    private var banner = 0f

    // ---- sound ----
    private val fx = SoundFx(context)
    private var groanTimer = 1.5f
    private var hurtSfxTimer = 0f

    private val bullets = ArrayList<Bullet>()
    private val zombies = ArrayList<Zombie>()
    private val particles = ArrayList<Particle>()
    private val pickups = ArrayList<Pickup>()

    // ---- input ----
    private var movePointer = -1
    private var moveOx = 0f
    private var moveOy = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var aimPointer = -1
    private var aimOx = 0f
    private var aimOy = 0f
    private var aimX = 0f
    private var aimY = 0f

    init {
        holder.addCallback(this)
        isFocusable = true
        fx.enabled = !prefs.getBoolean("muted", false)
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun onHostResume() {
        if (running) return
        running = true
        fx.resume()
        thread = Thread(this, "game-loop").also { it.start() }
    }

    fun onHostPause() {
        running = false
        thread?.join()
        thread = null
        fx.pause()
        synchronized(lock) {
            movePointer = -1
            aimPointer = -1
            if (state == State.PLAYING) state = State.PAUSED
        }
    }

    override fun onDetachedFromWindow() {
        fx.release()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            val first = !ready
            w = width
            h = height
            buildBackground()
            if (first) {
                ready = true
                resetGame()
                state = State.MENU
            } else {
                px = px.coerceIn(pr, w - pr)
                py = py.coerceIn(pr, h - pr)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min(0.05f, (now - last) / 1_000_000_000f)
            last = now
            if (ready && holder.surface.isValid) {
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(lock) {
                            update(dt)
                            render(canvas)
                        }
                    }
                } catch (e: Exception) {
                    // surface went away mid-frame; try again next frame
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            val spentMs = (System.nanoTime() - now) / 1_000_000L
            if (spentMs < 16L) {
                try {
                    Thread.sleep(16L - spentMs)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    // =====================================================================
    // Input
    // =====================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        synchronized(lock) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val id = e.getPointerId(i)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (state != State.PLAYING) {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) handleTap(x, y)
                    } else if (x < w / 2f) {
                        if (movePointer == -1) {
                            movePointer = id
                            moveOx = x; moveOy = y; moveX = x; moveY = y
                        }
                    } else {
                        if (aimPointer == -1) {
                            aimPointer = id
                            aimOx = x; aimOy = y; aimX = x; aimY = y
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) {
                        val id = e.getPointerId(i)
                        if (id == movePointer) {
                            moveX = e.getX(i); moveY = e.getY(i)
                        } else if (id == aimPointer) {
                            aimX = e.getX(i); aimY = e.getY(i)
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(e.actionIndex)
                    if (id == movePointer) movePointer = -1
                    if (id == aimPointer) aimPointer = -1
                }

                MotionEvent.ACTION_CANCEL -> {
                    movePointer = -1
                    aimPointer = -1
                }
            }
        }
        return true
    }

    private fun muteRect(): RectF {
        val size = 44f * density
        val left = w - 32f * density - size
        val top = 18f * density
        return RectF(left, top, left + size, top + size)
    }

    private fun handleTap(x: Float, y: Float) {
        movePointer = -1
        aimPointer = -1
        val r = muteRect()
        val pad = 12f * density
        if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) {
            fx.enabled = !fx.enabled
            prefs.edit().putBoolean("muted", !fx.enabled).apply()
            fx.play(Sfx.PICKUP, 0.6f)
            return
        }
        when (state) {
            State.MENU -> {
                resetGame()
                state = State.PLAYING
                stateTime = 0f
            }
            State.PAUSED -> {
                state = State.PLAYING
                stateTime = 0f
            }
            State.GAME_OVER -> {
                if (stateTime > 0.8f) {
                    resetGame()
                    state = State.PLAYING
                    stateTime = 0f
                }
            }
            State.PLAYING -> {}
        }
    }

    // =====================================================================
    // Game logic
    // =====================================================================

    private fun resetGame() {
        bullets.clear()
        zombies.clear()
        particles.clear()
        pickups.clear()
        px = w / 2f
        py = h / 2f
        hp = maxHp
        angle = 0f
        cooldown = 0f
        hurt = 0f
        shake = 0f
        walkCycle = 0f
        score = 0
        kills = 0
        wave = 0
        toSpawn = 0
        spawnTimer = 0f
        waveBreak = 1.0f
        banner = 0f
        groanTimer = 1.5f
        hurtSfxTimer = 0f
    }

    private fun update(dt: Float) {
        time += dt
        stateTime += dt
        updateParticles(dt)
        if (shake > 0f) shake = max(0f, shake - dt)
        if (state != State.PLAYING) return

        if (hurt > 0f) hurt -= dt
        if (banner > 0f) banner -= dt
        if (hurtSfxTimer > 0f) hurtSfxTimer -= dt
        updateGroans(dt)

        updatePlayer(dt)
        updateWaves(dt)
        updateBullets(dt)
        updateZombies(dt)
        updatePickups(dt)

        if (hp <= 0f) {
            hp = 0f
            state = State.GAME_OVER
            stateTime = 0f
            movePointer = -1
            aimPointer = -1
            shake = 0.35f
            fx.play(Sfx.GAME_OVER, 1f)
            burst(px, py, cNeon, 40, 260f)
            if (score > best) {
                best = score
                prefs.edit().putInt("best", best).apply()
            }
        }
    }

    private fun updatePlayer(dt: Float) {
        var mx = 0f
        var my = 0f
        if (movePointer != -1) {
            val dx = moveX - moveOx
            val dy = moveY - moveOy
            val len = hypot(dx, dy)
            if (len > stickR * 0.12f) {
                val s = min(1f, len / stickR)
                mx = dx / len * s
                my = dy / len * s
            }
        }
        val speed = 175f * density
        px = (px + mx * speed * dt).coerceIn(pr, w - pr)
        py = (py + my * speed * dt).coerceIn(pr, h - pr)
        if (mx != 0f || my != 0f) walkCycle += dt * 11f

        var aiming = false
        if (aimPointer != -1) {
            val dx = aimX - aimOx
            val dy = aimY - aimOy
            if (hypot(dx, dy) > stickR * 0.12f) {
                angle = atan2(dy, dx)
                aiming = true
            }
        } else if (mx != 0f || my != 0f) {
            angle = atan2(my, mx)
        }

        cooldown -= dt
        if (cooldown < 0f) cooldown = 0f
        if (aiming && cooldown <= 0f) {
            shoot()
            cooldown = max(0.09f, 0.24f - wave * 0.012f)
        }
    }

    private fun shoot() {
        val n = when {
            wave >= 8 -> 3
            wave >= 4 -> 2
            else -> 1
        }
        val spread = 0.13f
        val speed = 640f * density
        fx.play(Sfx.SHOT, 0.55f, 0f, 0.93f + Random.nextFloat() * 0.14f)
        for (i in 0 until n) {
            val a = angle + (i - (n - 1) / 2f) * spread + (Random.nextFloat() - 0.5f) * 0.05f
            bullets.add(
                Bullet(
                    px + cos(a) * pr * 1.7f,
                    py + sin(a) * pr * 1.7f,
                    cos(a) * speed,
                    sin(a) * speed,
                    0.9f
                )
            )
        }
        // muzzle flash
        particles.add(
            Particle(
                px + cos(angle) * pr * 1.8f, py + sin(angle) * pr * 1.8f,
                0f, 0f, 0.05f, 0.05f, cWhite, 5f * density
            )
        )
    }

    private fun startWave() {
        wave++
        toSpawn = 5 + wave * 3
        spawnTimer = 0f
        banner = 2f
        fx.play(Sfx.WAVE_START, 0.8f)
    }

    private fun updateWaves(dt: Float) {
        if (waveBreak > 0f) {
            waveBreak -= dt
            if (waveBreak <= 0f) startWave()
            return
        }
        if (toSpawn > 0) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnZombie()
                toSpawn--
                spawnTimer = max(0.3f, 1.1f - wave * 0.06f)
            }
        } else if (zombies.isEmpty()) {
            // wave cleared
            waveBreak = 2.5f
            hp = min(maxHp, hp + 15f)
            score += wave * 50
        }
    }

    private fun spawnZombie() {
        val margin = 30f * density
        var x: Float
        var y: Float
        when (Random.nextInt(4)) {
            0 -> { x = -margin; y = Random.nextFloat() * h }
            1 -> { x = w + margin; y = Random.nextFloat() * h }
            2 -> { x = Random.nextFloat() * w; y = -margin }
            else -> { x = Random.nextFloat() * w; y = h + margin }
        }
        val roll = Random.nextFloat()
        val type = when {
            wave >= 3 && roll < 0.12f -> 2
            wave >= 2 && roll < 0.35f -> 1
            else -> 0
        }
        val hpScale = 1f + (wave - 1) * 0.12f
        if (type == 2) fx.play(Sfx.ROAR, 0.9f, panOf(x))
        zombies.add(
            when (type) {
                0 -> Zombie(x, y, 30f * hpScale, (55f + Random.nextFloat() * 20f + wave * 3f) * density, 13f * density, 0, 10f)
                1 -> Zombie(x, y, 18f * hpScale, (115f + wave * 4f) * density, 10f * density, 1, 8f)
                else -> Zombie(x, y, 120f * hpScale, 42f * density, 20f * density, 2, 20f)
            }
        )
    }

    private fun updateBullets(dt: Float) {
        for (b in bullets) {
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.life -= dt
            if (b.x < -20f || b.x > w + 20f || b.y < -20f || b.y > h + 20f) b.life = 0f
        }
        val damage = 14f + wave * 0.8f
        for (b in bullets) {
            if (b.life <= 0f) continue
            for (z in zombies) {
                if (z.hp <= 0f) continue
                val r = z.radius + 3f * density
                val dx = z.x - b.x
                val dy = z.y - b.y
                if (dx * dx + dy * dy < r * r) {
                    z.hp -= damage
                    z.flash = 0.08f
                    z.x += b.vx * 0.012f
                    z.y += b.vy * 0.012f
                    b.life = 0f
                    burst(b.x, b.y, Color.rgb(150, 30, 30), 3, 120f)
                    fx.play(Sfx.HIT, 0.7f, panOf(b.x), 0.9f + Random.nextFloat() * 0.25f)
                    break
                }
            }
        }
        bullets.removeAll { it.life <= 0f }

        val iter = zombies.iterator()
        while (iter.hasNext()) {
            val z = iter.next()
            if (z.hp <= 0f) {
                score += when (z.type) {
                    0 -> 10
                    1 -> 15
                    else -> 40
                }
                kills++
                burst(z.x, z.y, Color.rgb(110, 20, 20), 14, 200f)
                val deathRate = when (z.type) {
                    1 -> 1.25f
                    2 -> 0.7f
                    else -> 0.9f + Random.nextFloat() * 0.2f
                }
                fx.play(Sfx.ZOMBIE_DEATH, 0.9f, panOf(z.x), deathRate)
                val dropChance = if (z.type == 2) 0.5f else 0.07f
                if (Random.nextFloat() < dropChance) pickups.add(Pickup(z.x, z.y, 10f))
                iter.remove()
            }
        }
    }

    private fun updateZombies(dt: Float) {
        for (z in zombies) {
            val dx = px - z.x
            val dy = py - z.y
            val dist = max(0.001f, hypot(dx, dy))
            z.x += dx / dist * z.speed * dt
            z.y += dy / dist * z.speed * dt
            if (z.flash > 0f) z.flash -= dt
            z.wobble += dt * 6f
            if (dist < z.radius + pr * 0.9f) {
                hp -= z.damage * dt * 1.2f
                hurt = 0.15f
                shake = max(shake, 0.1f)
                if (hurtSfxTimer <= 0f) {
                    fx.play(Sfx.HURT, 0.9f)
                    hurtSfxTimer = 0.4f
                }
            }
        }
        // keep zombies from stacking on one another
        for (i in 0 until zombies.size) {
            val a = zombies[i]
            for (j in i + 1 until zombies.size) {
                val b = zombies[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val minD = a.radius + b.radius
                val d2 = dx * dx + dy * dy
                if (d2 < minD * minD && d2 > 0.0001f) {
                    val dist = sqrt(d2)
                    val push = (minD - dist) * 0.5f
                    val nx = dx / dist
                    val ny = dy / dist
                    a.x -= nx * push; a.y -= ny * push
                    b.x += nx * push; b.y += ny * push
                }
            }
        }
    }

    private fun panOf(x: Float): Float = if (w > 0) ((x - w / 2f) / (w / 2f)).coerceIn(-1f, 1f) else 0f

    /** Random zombies groan now and then; more zombies means more groaning. */
    private fun updateGroans(dt: Float) {
        if (zombies.isEmpty()) return
        groanTimer -= dt
        if (groanTimer > 0f) return
        groanTimer = max(0.45f, 2.3f - zombies.size * 0.09f) * (0.6f + Random.nextFloat() * 0.8f)

        val z = zombies[Random.nextInt(zombies.size)]
        val dist = hypot(z.x - px, z.y - py)
        val far = hypot(w.toFloat(), h.toFloat())
        val vol = (1f - dist / far * 0.75f).coerceIn(0.25f, 0.9f)
        val sfx = when (Random.nextInt(3)) {
            0 -> Sfx.GROAN1
            1 -> Sfx.GROAN2
            else -> Sfx.GROAN3
        }
        val rate = when (z.type) {
            1 -> 1.3f + Random.nextFloat() * 0.15f
            2 -> 0.65f + Random.nextFloat() * 0.1f
            else -> 0.88f + Random.nextFloat() * 0.25f
        }
        fx.play(sfx, vol, panOf(z.x), rate)
    }

    private fun updatePickups(dt: Float) {
        for (p in pickups) {
            p.life -= dt
            if (hypot(p.x - px, p.y - py) < pr + 12f * density) {
                hp = min(maxHp, hp + 25f)
                score += 5
                p.life = 0f
                burst(p.x, p.y, cNeon, 10, 150f)
                fx.play(Sfx.PICKUP, 0.8f)
            }
        }
        pickups.removeAll { it.life <= 0f }
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            val drag = max(0f, 1f - 4f * dt)
            p.vx *= drag
            p.vy *= drag
            p.life -= dt
        }
        particles.removeAll { it.life <= 0f }
    }

    private fun burst(x: Float, y: Float, color: Int, count: Int, speed: Float) {
        if (particles.size > 400) return
        for (i in 0 until count) {
            val a = Random.nextFloat() * 6.2831855f
            val s = Random.nextFloat() * speed * density
            val life = 0.25f + Random.nextFloat() * 0.35f
            particles.add(
                Particle(x, y, cos(a) * s, sin(a) * s, life, life, color, (1.5f + Random.nextFloat() * 2.5f) * density)
            )
        }
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun hexPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        for (i in 0 until 6) {
            val a = Math.toRadians(60.0 * i - 90.0).toFloat()
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun buildBackground() {
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(cBg)

        // grid
        stroke.color = withAlpha(cNeon, 26)
        stroke.strokeWidth = 1f
        val step = 36f * density
        var x = 0f
        while (x <= w) {
            c.drawLine(x, 0f, x, h.toFloat(), stroke)
            x += step
        }
        var y = 0f
        while (y <= h) {
            c.drawLine(0f, y, w.toFloat(), y, stroke)
            y += step
        }

        // faint icon motif: double hexagon + "OX"
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.42f
        stroke.strokeWidth = 3f * density
        stroke.color = withAlpha(cNeon, 45)
        c.drawPath(hexPath(cx, cy, r), stroke)
        stroke.color = withAlpha(cBlue, 40)
        c.drawPath(hexPath(cx, cy, r * 0.72f), stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = r * 0.6f
        text.color = withAlpha(cWhite, 18)
        c.drawText("OX", cx, cy + r * 0.21f, text)

        bg = bmp
    }

    private fun drawLabel(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        text.textSize = size * density
        text.color = color
        text.textAlign = align
        c.drawText(s, x, y, text)
    }

    private fun render(c: Canvas) {
        c.drawColor(cBg)
        bg?.let { c.drawBitmap(it, 0f, 0f, null) }

        c.save()
        if (shake > 0f) {
            val m = shake * 40f * density
            c.translate((Random.nextFloat() - 0.5f) * m, (Random.nextFloat() - 0.5f) * m)
        }
        drawPickups(c)
        drawParticles(c)
        drawZombies(c)
        drawBullets(c)
        if (state != State.GAME_OVER) drawPlayer(c)
        c.restore()

        if (hurt > 0f) c.drawColor(Color.argb((hurt / 0.15f * 60f).toInt(), 255, 0, 0))

        if (state == State.PLAYING || state == State.PAUSED) {
            drawSticks(c)
            drawHud(c)
        }
        when (state) {
            State.MENU -> drawMenu(c)
            State.PAUSED -> drawPaused(c)
            State.GAME_OVER -> drawGameOver(c)
            State.PLAYING -> {
                if (banner > 0f) {
                    val a = (min(1f, banner) * 255f).toInt()
                    drawLabel(c, "WAVE $wave", w / 2f, h * 0.28f, 34f, withAlpha(cNeon, a))
                }
            }
        }
    }

    private fun drawPickups(c: Canvas) {
        for (p in pickups) {
            val pulse = 1f + 0.15f * sin(time * 6f)
            val r = 9f * density * pulse
            fill.color = withAlpha(cNeon, 60)
            c.drawCircle(p.x, p.y, r * 1.6f, fill)
            fill.color = cWhite
            c.drawRect(p.x - r, p.y - r * 0.3f, p.x + r, p.y + r * 0.3f, fill)
            c.drawRect(p.x - r * 0.3f, p.y - r, p.x + r * 0.3f, p.y + r, fill)
        }
    }

    private fun drawParticles(c: Canvas) {
        for (p in particles) {
            val a = (p.life / p.maxLife * 255f).toInt()
            fill.color = withAlpha(p.color, a)
            c.drawCircle(p.x, p.y, p.size, fill)
        }
    }

    private fun zombieColor(type: Int): Int = when (type) {
        0 -> Color.rgb(62, 122, 78)
        1 -> Color.rgb(122, 162, 58)
        else -> Color.rgb(47, 85, 96)
    }

    private fun drawZombies(c: Canvas) {
        for (z in zombies) {
            val a = atan2(py - z.y, px - z.x)
            c.save()
            c.translate(z.x, z.y)
            c.rotate(Math.toDegrees(a.toDouble()).toFloat())
            val body = if (z.flash > 0f) Color.WHITE else zombieColor(z.type)
            val swing = sin(z.wobble) * z.radius * 0.15f
            fill.color = body
            c.drawCircle(z.radius * 0.9f, -z.radius * 0.75f + swing, z.radius * 0.32f, fill)
            c.drawCircle(z.radius * 0.9f, z.radius * 0.75f - swing, z.radius * 0.32f, fill)
            c.drawCircle(0f, 0f, z.radius, fill)
            stroke.color = withAlpha(cBg, 200)
            stroke.strokeWidth = 1.5f * density
            c.drawCircle(0f, 0f, z.radius, stroke)
            fill.color = cRed
            c.drawCircle(z.radius * 0.45f, -z.radius * 0.35f, z.radius * 0.14f, fill)
            c.drawCircle(z.radius * 0.45f, z.radius * 0.35f, z.radius * 0.14f, fill)
            c.restore()
        }
    }

    private fun drawBullets(c: Canvas) {
        for (b in bullets) {
            fill.color = withAlpha(cNeon, 70)
            c.drawCircle(b.x, b.y, 5.5f * density, fill)
            fill.color = cWhite
            c.drawCircle(b.x, b.y, 2.4f * density, fill)
        }
    }

    private fun drawPlayer(c: Canvas) {
        val armor = Color.rgb(46, 58, 54)
        val armorHi = Color.rgb(66, 84, 78)
        val pants = Color.rgb(33, 41, 39)
        val helmet = Color.rgb(22, 28, 27)
        val visor = if (hurt > 0f) Color.rgb(255, 120, 120) else cNeon
        val gunColor = Color.rgb(18, 19, 21)

        // soft ground shadow, drawn unrotated so it doesn't spin with the body
        c.save()
        c.translate(px, py + pr * 0.35f)
        fill.color = withAlpha(Color.BLACK, 70)
        c.drawOval(RectF(-pr * 1.1f, -pr * 0.45f, pr * 1.1f, pr * 0.45f), fill)
        c.restore()

        c.save()
        c.translate(px, py)
        c.rotate(Math.toDegrees(angle.toDouble()).toFloat())

        // legs, with a small walk-cycle offset so they alternate while moving
        val legOff = pr * 0.22f * kotlin.math.sin(walkCycle)
        fill.color = pants
        c.drawRoundRect(RectF(-pr * 0.95f, -pr * 0.42f + legOff, -pr * 0.15f, -pr * 0.1f + legOff), pr * 0.12f, pr * 0.12f, fill)
        c.drawRoundRect(RectF(-pr * 0.95f, pr * 0.1f - legOff, -pr * 0.15f, pr * 0.42f - legOff), pr * 0.12f, pr * 0.12f, fill)

        // gun (drawn before the arms so the hands appear to grip it)
        fill.color = gunColor
        c.drawRoundRect(RectF(pr * 0.75f, -pr * 0.08f, pr * 2.05f, pr * 0.08f), pr * 0.05f, pr * 0.05f, fill)
        c.drawRect(pr * 1.0f, -pr * 0.28f, pr * 1.18f, -pr * 0.08f, fill)

        // torso
        fill.color = armor
        c.drawRoundRect(RectF(-pr * 0.55f, -pr * 0.78f, pr * 0.7f, pr * 0.78f), pr * 0.38f, pr * 0.38f, fill)
        fill.color = withAlpha(visor, 60)
        c.drawRoundRect(RectF(-pr * 0.3f, -pr * 0.14f, pr * 0.35f, pr * 0.14f), pr * 0.08f, pr * 0.08f, fill)

        // shoulder pads / arms reaching for the gun
        fill.color = armorHi
        c.drawCircle(-pr * 0.1f, -pr * 0.85f, pr * 0.3f, fill)
        c.drawCircle(-pr * 0.1f, pr * 0.85f, pr * 0.3f, fill)
        c.drawRoundRect(RectF(pr * 0.15f, -pr * 0.55f, pr * 0.95f, -pr * 0.18f), pr * 0.14f, pr * 0.14f, fill)
        c.drawRoundRect(RectF(pr * 0.15f, pr * 0.18f, pr * 0.95f, pr * 0.55f), pr * 0.14f, pr * 0.14f, fill)

        // head + visor, facing forward
        fill.color = helmet
        c.drawCircle(pr * 0.55f, 0f, pr * 0.52f, fill)
        fill.color = visor
        c.drawRoundRect(RectF(pr * 0.58f, -pr * 0.3f, pr * 1.0f, pr * 0.3f), pr * 0.14f, pr * 0.14f, fill)
        fill.color = withAlpha(Color.WHITE, 90)
        c.drawRoundRect(RectF(pr * 0.64f, -pr * 0.24f, pr * 0.8f, -pr * 0.1f), pr * 0.06f, pr * 0.06f, fill)

        stroke.color = withAlpha(cBg, 160)
        stroke.strokeWidth = 1.2f * density
        c.drawRoundRect(RectF(-pr * 0.55f, -pr * 0.78f, pr * 0.7f, pr * 0.78f), pr * 0.38f, pr * 0.38f, stroke)
        c.drawCircle(pr * 0.55f, 0f, pr * 0.52f, stroke)

        c.restore()
    }

    private fun drawStick(c: Canvas, ox: Float, oy: Float, tx: Float, ty: Float, color: Int, active: Boolean) {
        var dx = tx - ox
        var dy = ty - oy
        val len = hypot(dx, dy)
        if (len > stickR) {
            dx = dx / len * stickR
            dy = dy / len * stickR
        }
        stroke.strokeWidth = 2.5f * density
        stroke.color = withAlpha(color, if (active) 130 else 45)
        c.drawCircle(ox, oy, stickR, stroke)
        fill.color = withAlpha(color, if (active) 100 else 30)
        c.drawCircle(ox + dx, oy + dy, stickR * 0.42f, fill)
    }

    private fun drawSticks(c: Canvas) {
        if (movePointer != -1) drawStick(c, moveOx, moveOy, moveX, moveY, cNeon, true)
        else drawStick(c, w * 0.16f, h * 0.72f, w * 0.16f, h * 0.72f, cNeon, false)

        if (aimPointer != -1) drawStick(c, aimOx, aimOy, aimX, aimY, cRed, true)
        else drawStick(c, w * 0.84f, h * 0.72f, w * 0.84f, h * 0.72f, cRed, false)
    }

    private fun drawHud(c: Canvas) {
        val m = 32f * density
        // health bar
        val bw = 150f * density
        val bh = 12f * density
        val top = 18f * density
        fill.color = withAlpha(cWhite, 30)
        c.drawRect(m, top, m + bw, top + bh, fill)
        val frac = (hp / maxHp).coerceIn(0f, 1f)
        fill.color = if (frac > 0.3f) cNeon else cRed
        c.drawRect(m, top, m + bw * frac, top + bh, fill)
        stroke.color = withAlpha(cWhite, 140)
        stroke.strokeWidth = 1.5f * density
        c.drawRect(m, top, m + bw, top + bh, stroke)

        drawLabel(c, "SCORE $score", w / 2f, top + bh + 2f * density, 16f, cWhite)
        drawLabel(c, "WAVE $wave", w - m, top + bh + 2f * density, 16f, cNeon, Paint.Align.RIGHT)
        drawLabel(c, "BEST $best", w - m, top + bh + 22f * density, 11f, withAlpha(cWhite, 150), Paint.Align.RIGHT)
    }

    private fun drawMuteButton(c: Canvas) {
        val r = muteRect()
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.width() / 2f
        stroke.color = withAlpha(cWhite, 120)
        stroke.strokeWidth = 1.5f * density
        c.drawRoundRect(r, 8f * density, 8f * density, stroke)

        val sp = Path()
        sp.moveTo(cx - s * 0.6f, cy - s * 0.22f)
        sp.lineTo(cx - s * 0.3f, cy - s * 0.22f)
        sp.lineTo(cx + s * 0.05f, cy - s * 0.5f)
        sp.lineTo(cx + s * 0.05f, cy + s * 0.5f)
        sp.lineTo(cx - s * 0.3f, cy + s * 0.22f)
        sp.lineTo(cx - s * 0.6f, cy + s * 0.22f)
        sp.close()
        fill.color = cWhite
        c.drawPath(sp, fill)

        stroke.strokeWidth = 2f * density
        if (fx.enabled) {
            stroke.color = cNeon
            val ax = cx + s * 0.05f
            var rr = s * 0.3f
            c.drawArc(ax - rr, cy - rr, ax + rr, cy + rr, -45f, 90f, false, stroke)
            rr = s * 0.55f
            c.drawArc(ax - rr, cy - rr, ax + rr, cy + rr, -45f, 90f, false, stroke)
        } else {
            stroke.color = cRed
            c.drawLine(cx + s * 0.25f, cy - s * 0.3f, cx + s * 0.7f, cy + s * 0.3f, stroke)
            c.drawLine(cx + s * 0.25f, cy + s * 0.3f, cx + s * 0.7f, cy - s * 0.3f, stroke)
        }
    }

    private fun dim(c: Canvas, a: Int) {
        c.drawColor(Color.argb(a, 0, 0, 0))
    }

    private fun blink(): Boolean = ((time * 2f).toInt() % 2) == 0

    private fun drawMenu(c: Canvas) {
        dim(c, 110)
        drawLabel(c, "OX", w / 2f, h * 0.36f, 64f, cNeon)
        drawLabel(c, "ZOMBIE SHOOTER", w / 2f, h * 0.36f + 34f * density, 22f, cWhite)
        drawLabel(c, "LEFT THUMB: MOVE   -   RIGHT THUMB: AIM + FIRE", w / 2f, h * 0.62f, 12f, withAlpha(cWhite, 190))
        if (best > 0) drawLabel(c, "BEST  $best", w / 2f, h * 0.70f, 14f, cBlue)
        if (blink()) drawLabel(c, "TAP TO START", w / 2f, h * 0.84f, 20f, cNeon)
        drawMuteButton(c)
    }

    private fun drawPaused(c: Canvas) {
        dim(c, 150)
        drawLabel(c, "PAUSED", w / 2f, h * 0.45f, 36f, cWhite)
        if (blink()) drawLabel(c, "TAP TO RESUME", w / 2f, h * 0.60f, 18f, cNeon)
        drawMuteButton(c)
    }

    private fun drawGameOver(c: Canvas) {
        dim(c, 150)
        drawLabel(c, "YOU DIED", w / 2f, h * 0.32f, 44f, cRed)
        drawLabel(c, "SCORE $score", w / 2f, h * 0.46f, 24f, cWhite)
        drawLabel(c, "WAVE $wave   -   KILLS $kills   -   BEST $best", w / 2f, h * 0.56f, 13f, withAlpha(cWhite, 200))
        if (stateTime > 0.8f && blink()) drawLabel(c, "TAP TO RETRY", w / 2f, h * 0.76f, 20f, cNeon)
        drawMuteButton(c)
    }
}
