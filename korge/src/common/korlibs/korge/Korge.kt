package korlibs.korge

import korlibs.audio.sound.*
import korlibs.datastructure.iterators.*
import korlibs.event.*
import korlibs.graphics.log.*
import korlibs.image.color.*
import korlibs.image.format.*
import korlibs.inject.Injector
import korlibs.inject.InjectorContext
import korlibs.io.async.*
import korlibs.io.dynamic.*
import korlibs.io.file.std.*
import korlibs.io.resources.*
import korlibs.korge.input.*
import korlibs.korge.internal.*
import korlibs.korge.logger.*
import korlibs.korge.render.*
import korlibs.korge.resources.*
import korlibs.korge.scene.*
import korlibs.korge.stat.*
import korlibs.korge.view.*
import korlibs.logger.*
import korlibs.math.geom.*
import korlibs.memory.*
import korlibs.render.*
import korlibs.time.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.reflect.*

/**
 * Entry point for games written in Korge.
 * You have to call the [KorgeConfig] method by either providing some parameters, or a [KorgeConfig.Config] object.
 */
object KorgeRunner {
    suspend operator fun invoke(config: KorgeConfig) {
        RegisteredImageFormats.register(config.imageFormats)

        val iconPath = config.icon
        val imageFormats = config.imageFormats
        val entry = config.main
        val multithreaded = config.multithreaded
        val windowSize = config.windowSize

        if (!Platform.isJsBrowser) {
            configureLoggerFromProperties(localCurrentDirVfs["klogger.properties"])
        }
        val realGameWindow = (config.gameWindow ?: coroutineContext[GameWindow] ?: CreateDefaultGameWindow(GameWindowCreationConfig(multithreaded = multithreaded, fullscreen = config.fullscreen)))
        realGameWindow.bgcolor = config.backgroundColor ?: Colors.BLACK
        //println("Configure: ${width}x${height}")
        // @TODO: Configure should happen before loop. But we should ensure that all the korgw targets are ready for this
        //realGameWindow.configure(width, height, title, icon, fullscreen)
        realGameWindow.loop {
            val gameWindow = this
            if (Platform.isNative) println("Korui[0]")
            gameWindow.registerTime("configureGameWindow") {
                realGameWindow.configure(windowSize, config.title, null, config.fullscreen, config.backgroundColor ?: Colors.BLACK)
            }
            gameWindow.registerTime("setIcon") {
                try {
                    // Do nothing
                    when {
                        //iconDrawable != null -> this.icon = iconDrawable.render()
                        iconPath != null -> this.icon = resourcesVfs[iconPath!!].readBitmap(imageFormats)
                        else -> Unit
                    }
                } catch (e: Throwable) {
                    KorgeConfig.logger.error { "Couldn't get the application icon" }
                    e.printStackTrace()
                }
            }
            this.quality = quality
            if (Platform.isNative) println("CanvasApplicationEx.IN[0]")
            val input = Input()
            val stats = Stats()

            // Use this once Korgw is on 1.12.5
            //val views = Views(gameWindow.getCoroutineDispatcherWithCurrentContext() + SupervisorJob(), ag, injector, input, timeProvider, stats, gameWindow)
            val views: Views = Views(
                coroutineContext = coroutineContext + gameWindow.coroutineDispatcher + InjectorContext(config.injector) + SupervisorJob(),
                ag = if (config.debugAg) AGPrint() else ag,
                injector = config.injector,
                input = input,
                timeProvider = config.timeProvider,
                stats = stats,
                gameWindow = gameWindow,
                gameId = config.gameId,
                settingsFolder = config.settingsFolder,
                batchMaxQuads = config.batchMaxQuads,
                stageBuilder = config.stageBuilder
            ).also {
                it.init()
            }

            if (Platform.isJsBrowser) {
                Dyn.global["views"] = views
            }
            config.injector
                .mapInstance(ModuleArgs(config.args))
                .mapInstance(GameWindow::class, gameWindow)
                .mapInstance(KorgeConfig::class, config)
            views.debugViews = debug
            views.debugFontExtraScale = config.debugFontExtraScale
            views.debugFontColor = config.debugFontColor
            views.virtualWidth = config.virtualSize.width.toInt()
            views.virtualHeight = config.virtualSize.height.toInt()
            views.scaleAnchor = config.displayMode.scaleAnchor
            views.scaleMode = config.displayMode.scaleMode
            views.clipBorders = config.displayMode.clipBorders
            views.targetFps = config.targetFps
            //Korge.prepareViews(views, gameWindow, bgcolor != null, bgcolor ?: Colors.TRANSPARENT_BLACK)

            gameWindow.registerTime("prepareViews") {
                prepareViews(
                    views,
                    gameWindow,
                    bgcolor != null,
                    bgcolor ?: Colors.TRANSPARENT,
                    waitForFirstRender = true,
                    forceRenderEveryFrame = config.forceRenderEveryFrame,
                    configInjector = config.configInjector
                )
            }

            gameWindow.registerTime("completeViews") {
                completeViews(views)
            }
            views.launchImmediately {
                coroutineScope {
                    //println("coroutineContext: $coroutineContext")
                    //println("GameWindow: ${coroutineContext[GameWindow]}")
                    if (config.mainSceneClass != null) {
                        views.stage.sceneContainer().changeTo(config.mainSceneClass)
                    }
                    entry(views.stage)
                    if (config.blocking) {
                        // @TODO: Do not complete to prevent job cancelation?
                        gameWindow.waitClose()
                    }
                }
            }
            if (Platform.isNative) println("CanvasApplicationEx.IN[1]")
            if (Platform.isNative) println("Korui[1]")

            if (config.blocking) {
                // @TODO: Do not complete to prevent job cancelation?
                gameWindow.waitClose()
                gameWindow.exit()
            }
        }
    }

    suspend fun GameWindow.waitClose() {
        while (running) {
            delay(100.milliseconds)
        }
    }

    @KorgeInternal
    fun prepareViewsBase(
        views: Views,
        eventDispatcher: EventListener,
        clearEachFrame: Boolean = true,
        bgcolor: RGBA = Colors.TRANSPARENT,
        fixedSizeStep: TimeSpan = TimeSpan.NIL,
        forceRenderEveryFrame: Boolean = true,
        configInjector: Injector.() -> Unit = {},
    ): CompletableDeferred<Unit> {
        KorgeReload.registerEventDispatcher(eventDispatcher)

        val injector = views.injector
        injector.mapInstance(views)
        injector.mapInstance(views.ag)
        injector.mapInstance(Resources::class, views.globalResources)
        injector.mapSingleton(ResourcesRoot::class) { ResourcesRoot() }
        injector.mapInstance(views.input)
        injector.mapInstance(views.stats)
        injector.mapInstance(CoroutineContext::class, views.coroutineContext)
        injector.mapPrototype(EmptyScene::class) { EmptyScene() }
        injector.mapInstance(TimeProvider::class, views.timeProvider)
        configInjector(injector)

        val input = views.input
        val ag = views.ag
        var downPos = Point.ZERO
        var upPos = Point.ZERO
        var downTime = DateTime.EPOCH
        var moveTime = DateTime.EPOCH
        var upTime = DateTime.EPOCH
        var moveMouseOutsideInNextFrame = false
        val mouseTouchId = -1
        views.forceRenderEveryFrame = forceRenderEveryFrame

        // devicePixelRatio might change at runtime by changing the resolution or changing the screen of the window
        fun getRealXY(x: Float, y: Float, scaleCoords: Boolean): Point {
            return views.windowToGlobalCoords(Point(x, y))
        }

        fun getRealX(x: Float, scaleCoords: Boolean): Float = if (scaleCoords) x * views.devicePixelRatio else x
        fun getRealY(y: Float, scaleCoords: Boolean): Float = if (scaleCoords) y * views.devicePixelRatio else y

        /*
        fun updateTouch(id: Int, x: Double, y: Double, start: Boolean, end: Boolean) {
            val touch = input.getTouch(id)
            val now = DateTime.now()

            touch.id = id
            touch.active = !end

            if (start) {
                touch.startTime = now
                touch.start.setTo(x, y)
            }

            touch.currentTime = now
            touch.current.setTo(x, y)

            input.updateTouches()
        }
        */

        fun mouseDown(type: String, p: Point, button: MouseButton) {
            input.toggleButton(button, true)
            input.setMouseGlobalPos(p, down = false)
            input.setMouseGlobalPos(p, down = true)
            views.mouseUpdated()
            downPos = input.mousePos
            downTime = DateTime.now()
            input.mouseInside = true
        }

        fun mouseUp(type: String, p: Point, button: MouseButton) {
            //Console.log("mouseUp: $name")
            input.toggleButton(button, false)
            input.setMouseGlobalPos(p, down = false)
            views.mouseUpdated()
            upPos = views.input.mousePos
        }

        fun mouseMove(type: String, p: Point, inside: Boolean) {
            views.input.setMouseGlobalPos(p, down = false)
            views.input.mouseInside = inside
            if (!inside) {
                moveMouseOutsideInNextFrame = true
            }
            views.mouseUpdated()
            moveTime = DateTime.now()
        }

        fun mouseDrag(type: String, p: Point) {
            views.input.setMouseGlobalPos(p, down = false)
            views.mouseUpdated()
            moveTime = DateTime.now()
        }

        val mouseTouchEvent = TouchEvent()

        fun dispatchSimulatedTouchEvent(
            p: Point,
            button: MouseButton,
            type: TouchEvent.Type,
            status: Touch.Status
        ) {
            mouseTouchEvent.screen = 0
            mouseTouchEvent.emulated = true
            mouseTouchEvent.currentTime = DateTime.now()
            mouseTouchEvent.scaleCoords = false
            mouseTouchEvent.startFrame(type)
            mouseTouchEvent.touch(button.id, p, status, kind = Touch.Kind.MOUSE, button = button)
            mouseTouchEvent.endFrame()
            views.dispatch(mouseTouchEvent)
        }

        eventDispatcher.onEvents(*MouseEvent.Type.ALL) { e ->
            //println("MOUSE: $e")
            KorgeConfig.logger.trace { "eventDispatcher.addEventListener<MouseEvent>:$e" }
            val p = getRealXY(e.x.toFloat(), e.y.toFloat(), e.scaleCoords)
            when (e.type) {
                MouseEvent.Type.DOWN -> {
                    mouseDown("mouseDown", p, e.button)
                    //updateTouch(mouseTouchId, x, y, start = true, end = false)
                    dispatchSimulatedTouchEvent(p, e.button, TouchEvent.Type.START, Touch.Status.ADD)
                }

                MouseEvent.Type.UP -> {
                    mouseUp("mouseUp", p, e.button)
                    //updateTouch(mouseTouchId, x, y, start = false, end = true)
                    dispatchSimulatedTouchEvent(p, e.button, TouchEvent.Type.END, Touch.Status.REMOVE)
                }

                MouseEvent.Type.DRAG -> {
                    mouseDrag("onMouseDrag", p)
                    //updateTouch(mouseTouchId, x, y, start = false, end = false)
                    dispatchSimulatedTouchEvent(p, e.button, TouchEvent.Type.MOVE, Touch.Status.KEEP)
                }

                MouseEvent.Type.MOVE -> mouseMove("mouseMove", p, inside = true)
                MouseEvent.Type.CLICK -> Unit
                MouseEvent.Type.ENTER -> mouseMove("mouseEnter", p, inside = true)
                MouseEvent.Type.EXIT -> mouseMove("mouseExit", p, inside = false)
                MouseEvent.Type.SCROLL -> Unit
            }
            views.dispatch(e)
        }

        eventDispatcher.onEvents(*KeyEvent.Type.ALL) { e ->
            KorgeConfig.logger.trace { "eventDispatcher.addEventListener<KeyEvent>:$e" }
            views.dispatch(e)
        }
        eventDispatcher.onEvents(*GestureEvent.Type.ALL) { e ->
            KorgeConfig.logger.trace { "eventDispatcher.addEventListener<GestureEvent>:$e" }
            views.dispatch(e)
        }

        eventDispatcher.onEvents(*DropFileEvent.Type.ALL) { e -> views.dispatch(e) }
        eventDispatcher.onEvent(ResumeEvent) { e ->
            views.dispatch(e)
            nativeSoundProvider.paused = false
        }
        eventDispatcher.onEvent(PauseEvent) { e ->
            views.dispatch(e)
            nativeSoundProvider.paused = true
        }
        eventDispatcher.onEvent(StopEvent) { e -> views.dispatch(e) }
        eventDispatcher.onEvent(DestroyEvent) { e ->
            try {
                views.dispatch(e)
            } finally {
                views.launchImmediately {
                    views.close()
                }
            }
        }

        val touchMouseEvent = MouseEvent()
        eventDispatcher.onEvents(*TouchEvent.Type.ALL) { e ->
            KorgeConfig.logger.trace { "eventDispatcher.addEventListener<TouchEvent>:$e" }

            input.updateTouches(e)
            val ee = input.touch
            for (t in ee.touches) {
                t.p = getRealXY(t.x, t.y, e.scaleCoords)
            }
            views.dispatch(ee)

            // Touch to mouse events
            if (ee.numTouches == 1) {
                val start = ee.isStart
                val end = ee.isEnd
                val t = ee.touches.first()
                val p = t.p
                val x = t.x
                val y = t.y
                val button = MouseButton.LEFT

                //updateTouch(t.id, x, y, start, end)
                when {
                    start -> mouseDown("onTouchStart", p, button)
                    end -> mouseUp("onTouchEnd", p, button)
                    else -> mouseMove("onTouchMove", p, inside = true)
                }
                views.dispatch(touchMouseEvent.also {
                    it.id = 0
                    it.button = button
                    it.buttons = if (end) 0 else 1 shl button.id
                    it.x = x.toInt()
                    it.y = y.toInt()
                    it.scaleCoords = false
                    it.emulated = true
                    it.type = when {
                        start -> MouseEvent.Type.DOWN
                        end -> MouseEvent.Type.UP
                        else -> MouseEvent.Type.DRAG
                    }
                })
                if (end) {
                    moveMouseOutsideInNextFrame = true
                }
            }

        }

        fun gamepadUpdated(e: GamePadUpdateEvent) {
            e.gamepads.fastForEach { gamepad ->
                input.gamepads[gamepad.index].copyFrom(gamepad)
            }
            input.updateConnectedGamepads()
        }

        eventDispatcher.onEvents(*GamePadConnectionEvent.Type.ALL) { e ->
            KorgeConfig.logger.trace { "eventDispatcher.addEventListener<GamePadConnectionEvent>:$e" }
            views.dispatch(e)
        }

        eventDispatcher.onEvent(GamePadUpdateEvent) { e ->
            gamepadUpdated(e)
            views.dispatch(e)
        }

        eventDispatcher.onEvent(ReshapeEvent) { e ->
            //try { throw Exception() } catch (e: Throwable) { e.printStackTrace() }
            //println("eventDispatcher.addEventListener<ReshapeEvent>: ${ag.backWidth}x${ag.backHeight} : ${e.width}x${e.height}")
            //println("resized. ${ag.backWidth}, ${ag.backHeight}")
            views.resized(ag.mainFrameBuffer.width, ag.mainFrameBuffer.height)
        }

        //println("eventDispatcher.dispatch(ReshapeEvent(0, 0, views.nativeWidth, views.nativeHeight)) : ${views.nativeWidth}x${views.nativeHeight}")
        eventDispatcher.dispatch(ReshapeEvent(0, 0, views.nativeWidth, views.nativeHeight))

        eventDispatcher.onEvent(ReloadEvent) { views.dispatch(it) }

        var renderShown = false
        views.clearEachFrame = clearEachFrame
        views.clearColor = bgcolor
        val firstRenderDeferred = CompletableDeferred<Unit>()

        fun renderBlock(event: RenderEvent) {
            //println("renderBlock: $event")
            try {
                views.frameUpdateAndRender(
                    fixedSizeStep = fixedSizeStep,
                    forceRender = views.forceRenderEveryFrame,
                    doUpdate = event.update,
                    doRender = event.render,
                )

                views.input.mouseOutside = false
                if (moveMouseOutsideInNextFrame) {
                    moveMouseOutsideInNextFrame = false
                    views.input.mouseOutside = true
                    views.input.mouseInside = false
                    views.mouseUpdated()
                }
            } catch (e: Throwable) {
                KorgeConfig.logger.error { "views.gameWindow.onRenderEvent:" }
                e.printStackTrace()
                if (views.rethrowRenderError) throw e
            }
        }

        views.gameWindow.onRenderEvent { event ->
            //println("RenderEvent: $event")
            if (!event.render) {
                renderBlock(event)
            } else {
                views.renderContext.doRender {
                    if (!renderShown) {
                        //println("!!!!!!!!!!!!! views.gameWindow.addEventListener<RenderEvent>")
                        renderShown = true
                        firstRenderDeferred.complete(Unit)
                    }
                    renderBlock(event)
                }
            }
        }

        return firstRenderDeferred
    }

    @KorgeInternal
    suspend fun prepareViews(
        views: Views,
        eventDispatcher: EventListener,
        clearEachFrame: Boolean = true,
        bgcolor: RGBA = Colors.TRANSPARENT,
        fixedSizeStep: TimeSpan = TimeSpan.NIL,
        waitForFirstRender: Boolean = true,
        forceRenderEveryFrame: Boolean = true,
        configInjector: Injector.() -> Unit
    ) {
        val firstRenderDeferred =
            prepareViewsBase(views, eventDispatcher, clearEachFrame, bgcolor, fixedSizeStep, forceRenderEveryFrame, configInjector)
        if (waitForFirstRender) {
            firstRenderDeferred.await()
        }
    }

	data class ModuleArgs(val args: Array<String>)
}
