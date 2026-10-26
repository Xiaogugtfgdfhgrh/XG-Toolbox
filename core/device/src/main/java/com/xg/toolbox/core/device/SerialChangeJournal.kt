package com.xg.toolbox.core.device

import android.content.Context
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import dagger.hilt.internal.GeneratedComponentManager
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Provider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface SerialChangeJournal {

    
    suspend fun snapshot(kind: String, target: String, payload: String, ttlMs: Long): XgResult<Long>

    
    suspend fun audit(
        category: String,
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long?,
    ): XgResult<Unit>
}

internal class HiltSerialChangeJournal(private val context: Context) : SerialChangeJournal {

    private val resolved: Resolved? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { resolve() }

    override suspend fun snapshot(
        kind: String,
        target: String,
        payload: String,
        ttlMs: Long,
    ): XgResult<Long> {
        val repo = resolved?.snapshot
            ?: return unavailable("快照")
        return try {
            val value = invokeSuspend(repo.target, repo.method, kind, target, payload, ttlMs)
            val id = (value as? Number)?.toLong()
                ?: return XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "快照组件返回了无法识别的 id（${value?.javaClass?.name ?: "null"}）",
                )
            XgResult.Ok(id)
        } catch (t: Throwable) {
            XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                "写入序列号快照失败：${t.message ?: t.javaClass.simpleName}",
                t.javaClass.name,
            )
        }
    }

    override suspend fun audit(
        category: String,
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long?,
    ): XgResult<Unit> {
        val repo = resolved?.audit
            ?: return unavailable("审计")
        return try {
            invokeSuspend(
                repo.target,
                repo.method,
                category,
                action,
                target,
                commands,
                result,
                message,
                snapshotId,
            )
            XgResult.Ok(Unit)
        } catch (t: Throwable) {
            XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "写入序列号审计失败：${t.message ?: t.javaClass.simpleName}",
                t.javaClass.name,
            )
        }
    }

    private fun unavailable(what: String): XgResult.Err = XgResult.Err(
        ErrorCode.ERR_CMD_FAILED,
        "${what}组件不可用，已拒绝修改序列号",
        "未从 Hilt SingletonComponent 解析到${what}组件",
    )

    private fun resolve(): Resolved? {
        val component = component() ?: return null
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val candidates = ArrayList<Any>()

        component.javaClass.methods.forEach { method ->
            if (!isUsableAccessor(method)) return@forEach
            addResult(invokeQuietly { method.invoke(component) }, component, candidates, seen)
        }

        var type: Class<*>? = component.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredMethods.forEach { method ->
                if (!isUsableAccessor(method)) return@forEach
                val value = try {
                    method.isAccessible = true
                    method.invoke(component)
                } catch (t: Throwable) {
                    null
                }
                addResult(value, component, candidates, seen)
            }
            type = type.superclass
        }

        val fields = providerFields(component)
        val typed = fields.filter { field ->
            val signature = runCatching { field.genericType.typeName }.getOrDefault("")
            signature.contains("SnapshotRepository") || signature.contains("AuditRepository")
        }
        typed.forEach { field -> addProvider(component, field, candidates, seen) }

        var snapshot = findSnapshot(candidates)
        var audit = findAudit(candidates)
        if (snapshot == null || audit == null) {
            fields.filterNot { it in typed }.forEach { field ->
                addProvider(component, field, candidates, seen)
            }
            if (snapshot == null) snapshot = findSnapshot(candidates)
            if (audit == null) audit = findAudit(candidates)
        }
        return Resolved(snapshot = snapshot, audit = audit)
    }

    private fun isUsableAccessor(method: Method): Boolean {
        if (method.parameterCount != 0) return false
        if (method.name == "toString" || method.name == "hashCode" || method.name == "getClass") return false
        val type = method.returnType
        return type != Void.TYPE && !type.isPrimitive && !type.isArray
    }

    private fun component(): Any? = try {
        val app = context.applicationContext
        (app as? GeneratedComponentManager<*>)?.generatedComponent()
    } catch (t: Throwable) {
        null
    }

    private fun providerFields(component: Any): List<Field> {
        val fields = ArrayList<Field>()
        var type: Class<*>? = component.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (Provider::class.java.isAssignableFrom(field.type)) fields += field
            }
            type = type.superclass
        }
        return fields
    }

    private fun addProvider(
        component: Any,
        field: Field,
        out: MutableList<Any>,
        seen: MutableSet<Any>,
    ) {
        val value = try {
            field.isAccessible = true
            (field.get(component) as? Provider<*>)?.get()
        } catch (t: Throwable) {
            null
        }
        addResult(value, component, out, seen)
    }

    private fun addResult(value: Any?, component: Any, out: MutableList<Any>, seen: MutableSet<Any>) {
        if (value == null || value === component) return
        if (seen.add(value)) out += value
    }

    private fun findSnapshot(candidates: List<Any>): SuspendTarget? =
        findSuspendMethod(candidates, SNAPSHOT_PARAMETERS)

    private fun findAudit(candidates: List<Any>): SuspendTarget? =
        findSuspendMethod(candidates, AUDIT_PARAMETERS)

    private fun findSuspendMethod(candidates: List<Any>, expected: Array<Class<*>>): SuspendTarget? {
        for (candidate in candidates) {
            val method = candidate.javaClass.methods.firstOrNull { method ->
                method.parameterCount == expected.size + 1 &&
                    expected.indices.all { index -> method.parameterTypes[index] == expected[index] } &&
                    Continuation::class.java.isAssignableFrom(method.parameterTypes.last()) &&
                    method.returnType == Any::class.java
            } ?: continue
            return SuspendTarget(candidate, method)
        }
        return null
    }

    private suspend fun invokeSuspend(target: Any, method: Method, vararg args: Any?): Any? =
        suspendCancellableCoroutine { continuation ->
            try {
                val fullArgs = arrayOf(*args, continuation)
                val result = method.invoke(target, *fullArgs)
                if (result !== COROUTINE_SUSPENDED) {
                    continuation.resumeWith(Result.success(result))
                }
            } catch (t: InvocationTargetException) {
                continuation.resumeWith(Result.failure(t.targetException ?: t))
            } catch (t: Throwable) {
                continuation.resumeWith(Result.failure(t))
            }
        }

    private inline fun invokeQuietly(block: () -> Any?): Any? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    private class Resolved(
        val snapshot: SuspendTarget?,
        val audit: SuspendTarget?,
    )

    private class SuspendTarget(
        val target: Any,
        val method: Method,
    )

    private companion object {
        val SNAPSHOT_PARAMETERS: Array<Class<*>> = arrayOf(
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long.TYPE,
        )

        val AUDIT_PARAMETERS: Array<Class<*>> = arrayOf(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Long::class.java,
        )
    }
}
