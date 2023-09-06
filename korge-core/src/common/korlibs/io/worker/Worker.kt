package korlibs.io.worker

import korlibs.io.async.*
import korlibs.io.lang.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.js.*
import kotlin.reflect.*

@PublishedApi
internal expect val workerImpl: _WorkerImpl

val DEBUG_WORKER = Environment["DEBUG_WORKER"] == "true"

open class _WorkerImpl {
    open fun insideWorker(): Boolean = false
    open fun createWorker(): Any? = null
    open fun destroyWorker(worker: Any?) = Unit
    open suspend fun execute(worker: Any?, clazz: KClass<out WorkerTask>, params: Array<out Any?>): Any? = Unit
}

@JsExport
class DemoWorkerTask : WorkerTask() {
    override fun execute() = runSuspend {
        println("TEST!!!!! $params")
        //delay(1.seconds)
        //error("ERROR!")
        return@runSuspend 11
    }
}

open class WorkerTask {
    private var stackTrace: String? = null
    private var gettingStackTrace = false
    var params = listOf<Any?>()
        get() {
            if (!runSuspend) error("Must wrap function around runSuspend")
            return field
        }
    var result: Any? = null
    private var runSuspend = false
    protected fun runSuspend(block: suspend () -> Any?) {
        runSuspend = true
        try {
            if (gettingStackTrace) {
                stackTrace = currentStackTrace()
                return
            }
            val deferred = CompletableDeferred<Any?>()
            launchImmediately(EmptyCoroutineContext) {
                deferred.completeWith(runCatching { block() })
            }
            result = deferred
        } finally {
            runSuspend = false
        }
    }
    open fun execute() = Unit
    fun getModuleStacktrace(): String {
        gettingStackTrace = true
        try {
            execute()
            return stackTrace!!
        } finally {
            gettingStackTrace = false
        }
    }
}

class Worker : Closeable {
    val id = workerImpl.createWorker()

    suspend inline fun <reified T : WorkerTask> execute(vararg params: Any?): Any? {
        return workerImpl.execute(id, T::class, params)
    }

    companion object {
        inline fun init(block: () -> Unit): Unit {
            if (workerImpl.insideWorker()) {
                if (DEBUG_WORKER) println("INSIDE WORKER")
                return
            } else {
                if (DEBUG_WORKER) println("**NOT** INSIDE WORKER")
            }
            block()
        }

        //fun register(kClass: KClass<out WorkerTask>) {
        //    println("REGISTER: $kClass")
        //}
    }

    override fun close() {
        workerImpl.destroyWorker(id)
    }
}
