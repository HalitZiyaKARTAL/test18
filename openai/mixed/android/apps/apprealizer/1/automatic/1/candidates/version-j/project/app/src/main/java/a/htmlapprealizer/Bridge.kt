package a.htmlapprealizer

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

object BridgeFacade {
    @JavascriptInterface fun epoch(): String =
        if (Core.validateEpoch(requireAuth = false) == null) Core.currentEpoch() else ""

    @JavascriptInterface fun auth(password: String): Boolean = Core.authenticate(password)
    @JavascriptInterface fun boundary(epoch: String, stage: String): Boolean =
        Core.documentBoundary(epoch, stage)

    @JavascriptInterface fun q(
        epoch: String,
        target: String,
        member: String,
        json: String,
        callback: Int
    ): String = if (callback > 0) Core.async("java", target, member, json, callback)
    else Core.executeJava(target, member, json, epoch)

    @JavascriptInterface fun k(
        epoch: String,
        target: String,
        member: String,
        json: String,
        callback: Int
    ): String = if (callback > 0) Core.async("kotlin", target, member, json, callback)
    else Core.executeKotlin(target, member, json, epoch)

    @JavascriptInterface fun tsv(epoch: String, source: String, callback: Int): String {
        Core.validateEpoch(epoch)?.let { return it }
        return Core.runTsv(source, callback)
    }

    @JavascriptInterface fun listen(enabled: Boolean): Boolean = Core.setEvents(enabled)
    @JavascriptInterface fun caps(): String =
        if (Core.validateEpoch() == null) "V${Core.capabilities()}" else "E:SEC"
    @JavascriptInterface fun debug(): String =
        if (Core.validateEpoch() == null) "V${DebugLog.report()}" else "E:SEC"
    @JavascriptInterface fun drops(): Int = if (Core.validateEpoch() == null) Core.droppedEvents() else -1

    // Original K.* compatibility facade. P0 remains the current Activity (or app context).
    @JavascriptInterface fun sz(): Int = if (Core.validateEpoch() == null) Core.heapSize() + 1 else -1
    @JavascriptInterface fun cls() { if (Core.validateEpoch() == null) Core.clearHandles() }
    @JavascriptInterface fun c(name: String): String = Core.javaLookup(name)
    @JavascriptInterface fun n(className: String, json: String): String =
        Core.executeJava(className, "", json)
    @JavascriptInterface fun x(handle: Int, name: String, json: String): String =
        Core.executeJava("P$handle", name, json)
    @JavascriptInterface fun u(handle: Int, name: String, json: String, callback: Int) {
        val status = Core.async("java", "P$handle", "!$name", json, callback, legacy = true)
        if (!status.startsWith('W'))
            Core.result("return", callback, status, Core.currentEpoch(), legacy = true)
    }
    @JavascriptInterface fun g(handle: Int, field: String): String =
        Core.executeJava("P$handle", "#get:$field", "[]")
    @JavascriptInterface fun s(handle: Int, field: String, value: String): String =
        Core.executeJava("P$handle", "#set:$field", "[$value]")
    @JavascriptInterface fun a(type: String, length: Int): String =
        Core.executeJava(type, "#array", "[$length]")
    @JavascriptInterface fun p(type: String, callback: Int): String =
        legacyProxy(type, callback)
    @JavascriptInterface fun del(handle: Int) {
        if (Core.validateEpoch() == null && handle > 0) Core.drop("P$handle")
    }

    private fun legacyProxy(typeName: String, callback: Int): String {
        Core.validateEpoch()?.let { return it }
        val type = runCatching { Class.forName(typeName, false, javaClass.classLoader) }
            .getOrElse { return "E:CLASS" }
        if (!type.isInterface) return "E:TYPE"
        val request = BridgeRequest(
            "java", "proxy", type.name, "legacy:$callback", "java:proxy:${type.name}"
        )
        if (!Core.authorized(request)) return "E:POLICY"
        val born = Core.currentEpoch()
        val proxy = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxyObject, method, values ->
            if (method.declaringClass == Any::class.java) return@newProxyInstance when (method.name) {
                "toString" -> "LegacyBridgeProxy($callback)"
                "hashCode" -> System.identityHashCode(proxyObject)
                "equals" -> proxyObject === values?.firstOrNull()
                else -> null
            }
            Core.legacyProxy(callback, method.name, values, born)
            if (!method.returnType.isPrimitive || method.returnType == Void.TYPE) null
            else java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(method.returnType, 1), 0)
        }
        return Core.keep(proxy, born)
    }

    @JavascriptInterface fun cap(name: String): String {
        Core.validateEpoch()?.let { return it }
        val value = Core.portValue(name) ?: return "E:PORT"
        val request = BridgeRequest("portal", "port", value.javaClass.name, name, "portal:$name")
        return if (Core.authorized(request)) Core.keep(value) else "E:POLICY"
    }

    @JavascriptInterface fun manifest(): String = CapabilityFacade.manifest()
    @JavascriptInterface fun resources(): String = CapabilityFacade.resources()
    @JavascriptInterface fun root(): String = CapabilityFacade.root()
    @JavascriptInterface fun permission(permission: String, requestCode: Int): String =
        CapabilityFacade.requestPermission(permission, requestCode)
    @JavascriptInterface fun stop(portal: String): String = CapabilityFacade.stop(portal)
    @JavascriptInterface fun overlay(html: String, mode: String, configuration: String): String =
        CapabilityFacade.overlay(html, mode, configuration)
    @JavascriptInterface fun overlayEval(script: String): String = CapabilityFacade.overlayEval(script)
    @JavascriptInterface fun overlayClose(): String = CapabilityFacade.overlayClose()
    @JavascriptInterface fun vibe(durationMs: Long): String = CapabilityFacade.vibrate(durationMs)
    @JavascriptInterface fun notify(id: Int, title: String, text: String): String =
        CapabilityFacade.notify(id, title, text)
    @JavascriptInterface fun alarm(
        delayMs: Long, title: String, text: String, exact: Boolean
    ): String = CapabilityFacade.alarm(delayMs, title, text, exact)
}

object KotlinDynamic {
    private data class KotlinMatch(
        val callable: Any,
        val arguments: Map<Any, Any?>,
        val score: Int,
        val signature: String
    )

    fun execute(target: String, memberRaw: String, json: String, born: String): String {
        if (!BuildConfig.HAS_KOTLIN_REFLECT) return "E:KOTLIN_DEP"
        val resolved = Core.resolveReflectionTarget(target) ?: return "E:HANDLE"
        return try {
            val kClass = kotlinClass(resolved.type)
            when {
                memberRaw == "+" -> Core.retain(target)
                memberRaw == "-" -> if (Core.drop(target)) "V" else "E:HANDLE"
                memberRaw == "@object" -> objectValue(kClass, resolved.type, born)
                memberRaw == "@companion" -> companionValue(kClass, resolved.type, born)
                memberRaw.startsWith("get:") -> propertyGet(
                    kClass, resolved, memberRaw.removePrefix("get:"), born
                )
                memberRaw.startsWith("set:") -> propertySet(
                    kClass, resolved, memberRaw.removePrefix("set:"), json, born
                )
                else -> callable(kClass, resolved, memberRaw, json, born)
            }
        } catch (error: ClassNotFoundException) {
            DebugLog.add("KOTLIN", "kotlin-reflect classes absent", error)
            "E:KOTLIN_DEP"
        } catch (error: Throwable) {
            Core.bridgeFailure(if (error is InvocationTargetException) error.targetException else error)
        }
    }

    private fun kotlinClass(type: Class<*>): Any {
        val mapping = Class.forName("kotlin.jvm.JvmClassMappingKt")
        val method = mapping.methods.first {
            it.name == "getKotlinClass" && it.parameterCount == 1 && Modifier.isStatic(it.modifiers)
        }
        return method.invoke(null, type) ?: error("KClass mapping returned null")
    }

    private fun call0(value: Any, name: String): Any? =
        value.javaClass.methods.first { it.name == name && it.parameterCount == 0 }.invoke(value)

    private fun callBy(callable: Any, arguments: Map<Any, Any?>): Any? =
        callable.javaClass.methods.first { it.name == "callBy" && it.parameterCount == 1 }
            .invoke(callable, arguments)

    private fun callableName(value: Any): String = call0(value, "getName")?.toString().orEmpty()
    private fun signature(value: Any): String = "kotlin:${value}"

    private fun members(kClass: Any): Collection<Any> =
        (call0(kClass, "getMembers") as? Collection<*>)?.filterNotNull().orEmpty()

    private fun constructors(kClass: Any): Collection<Any> =
        (call0(kClass, "getConstructors") as? Collection<*>)?.filterNotNull().orEmpty()

    private fun isProperty(value: Any): Boolean =
        value.javaClass.methods.any { it.name == "getGetter" && it.parameterCount == 0 }

    private fun request(
        operation: String,
        type: Class<*>,
        member: String,
        callable: Any
    ) = BridgeRequest("kotlin", operation, type.name, member, signature(callable))

    private fun objectValue(kClass: Any, type: Class<*>, born: String): String {
        val policy = BridgeRequest("kotlin", "object", type.name, "object", "kotlin:object:${type.name}")
        if (!Core.authorized(policy)) return "E:POLICY"
        return call0(kClass, "getObjectInstance")?.let { Core.keep(it, born) } ?: "E:NOT_OBJECT"
    }

    private fun companionValue(kClass: Any, type: Class<*>, born: String): String {
        val nested = (call0(kClass, "getNestedClasses") as? Collection<*>)?.filterNotNull().orEmpty()
        val companion = nested.firstOrNull { call0(it, "getSimpleName") == "Companion" }
            ?: return "E:NO_COMPANION"
        val policy = BridgeRequest(
            "kotlin", "companion", type.name, "Companion", "kotlin:companion:${type.name}"
        )
        if (!Core.authorized(policy)) return "E:POLICY"
        return call0(companion, "getObjectInstance")?.let { Core.keep(it, born) } ?: "E:NO_COMPANION"
    }

    private fun propertyGet(
        kClass: Any,
        target: Core.ReflectionTarget,
        name: String,
        born: String
    ): String {
        val property = members(kClass).firstOrNull { isProperty(it) && callableName(it) == name }
            ?: return "E:PROPERTY"
        if (!Core.authorized(request("property-get", target.type, name, property))) return "E:POLICY"
        val matched = buildMatch(property, target, JSONObject(), JSONArray()) ?: return "E:SIG"
        return Core.keep(callBy(property, matched.arguments), born)
    }

    private fun propertySet(
        kClass: Any,
        target: Core.ReflectionTarget,
        name: String,
        json: String,
        born: String
    ): String {
        val property = members(kClass).firstOrNull { isProperty(it) && callableName(it) == name }
            ?: return "E:PROPERTY"
        val setter = runCatching { call0(property, "getSetter") }.getOrNull() ?: return "E:IMMUTABLE"
        if (!Core.authorized(request("property-set", target.type, name, setter))) return "E:POLICY"
        val array = JSONArray(json)
        val matched = buildMatch(setter, target, JSONObject(), array) ?: return "E:SIG"
        return Core.keep(callBy(setter, matched.arguments), born)
    }

    private fun callable(
        kClass: Any,
        target: Core.ReflectionTarget,
        member: String,
        json: String,
        born: String
    ): String {
        val constructor = member.isBlank() || member == "<init>"
        val candidates = if (constructor) constructors(kClass)
        else members(kClass).filter { !isProperty(it) && callableName(it) == member }
        val objectArguments = if (json.trimStart().startsWith('{')) JSONObject(json) else JSONObject()
        val arrayArguments = if (json.trimStart().startsWith('{')) JSONArray() else JSONArray(json)
        val matches = candidates.mapNotNull { candidate ->
            buildMatch(candidate, target.takeUnless { constructor }, objectArguments, arrayArguments)
        }.sortedWith(compareBy({ it.score }, { it.signature }))
        val selected = matches.firstOrNull() ?: return "E:SIG"
        val operation = if (constructor) "constructor" else "function"
        if (!Core.authorized(request(operation, target.type, member, selected.callable))) return "E:POLICY"
        return Core.keep(callBy(selected.callable, selected.arguments), born)
    }

    private fun buildMatch(
        callable: Any,
        target: Core.ReflectionTarget?,
        named: JSONObject,
        positional: JSONArray
    ): KotlinMatch? = runCatching {
        val parameters = (call0(callable, "getParameters") as? Collection<*>)?.filterNotNull().orEmpty()
        val output = linkedMapOf<Any, Any?>()
        var index = 0
        var score = 0
        for (parameter in parameters) {
            val kind = call0(parameter, "getKind").toString()
            if (kind == "INSTANCE") {
                val receiver = target?.let {
                    if (it.direct) call0(kotlinClass(it.type), "getObjectInstance") else it.receiver
                } ?: return@runCatching null
                output[parameter] = receiver
                continue
            }
            val name = call0(parameter, "getName")?.toString()
            val present = name != null && named.has(name)
            if (!present && index >= positional.length()) {
                val optional = call0(parameter, "isOptional") as? Boolean ?: false
                if (optional) continue
                return@runCatching null
            }
            val raw = if (present) named.get(name) else positional.get(index++)
            val javaType = javaType(call0(parameter, "getType") ?: return@runCatching null)
            val converted = Core.coerceFor(raw, javaType)
            output[parameter] = converted.first
            score += converted.second
        }
        if (index != positional.length()) return@runCatching null
        KotlinMatch(callable, output, score, signature(callable))
    }.getOrNull()

    private fun javaType(kType: Any): Class<*> {
        val classifier = call0(kType, "getClassifier") ?: return Any::class.java
        val mapping = Class.forName("kotlin.jvm.JvmClassMappingKt")
        val nullable = call0(kType, "isMarkedNullable") as? Boolean ?: true
        val methodName = if (nullable) "getJavaObjectType" else "getJavaClass"
        val method: Method = mapping.methods.first {
            it.name == methodName && it.parameterCount == 1 && Modifier.isStatic(it.modifiers)
        }
        return method.invoke(null, classifier) as Class<*>
    }
}

object KotlinProbe {
    var label: String = "version-j"
    fun describe(prefix: String = "probe", repeat: Int = 2): String =
        List(repeat.coerceIn(0, 20)) { "$prefix:$label" }.joinToString("|")
}

class KotlinSample(val base: Int = 7) {
    val doubled: Int get() = base * 2
    companion object {
        fun identify(tag: String = "J"): String = "sample-$tag"
    }
}
