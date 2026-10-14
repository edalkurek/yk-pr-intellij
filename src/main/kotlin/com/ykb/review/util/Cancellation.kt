package com.ykb.review.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager

object Cancellation {

    /** İlerleme göstergesi iptal edildiyse ProcessCanceledException fırlatır. Test ortamında (Application yok) no-op. */
    fun check() {
        if (ApplicationManager.getApplication() != null) {
            ProgressManager.checkCanceled()
        }
    }

    /** İptal göstergesi olan bir bağlamda çalışılıyorsa iptal durumunu döner. */
    fun isCanceled(): Boolean {
        if (ApplicationManager.getApplication() == null) return false
        return ProgressManager.getGlobalProgressIndicator()?.isCanceled == true
    }

    /** İptal edilebilir uyku: 100 ms aralıklarla iptal kontrolü yapar. */
    fun sleep(delayMs: Long) {
        var remaining = delayMs
        while (remaining > 0) {
            check()
            val step = minOf(100L, remaining)
            Thread.sleep(step)
            remaining -= step
        }
        check()
    }
}

/** IDE kontrol akışı istisnalarını (iptal vb.) asla yutmamak için catch bloklarının başında çağrılır. */
fun Throwable.rethrowIfControlFlow() {
    if (this is ProcessCanceledException || this is ControlFlowException || this is java.util.concurrent.CancellationException) {
        throw this
    }
}

fun Throwable.safeMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
