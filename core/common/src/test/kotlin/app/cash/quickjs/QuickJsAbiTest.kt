package app.cash.quickjs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class QuickJsAbiTest {

    @Test
    fun `matches extension-facing method ABI`() {
        QuickJs::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { methodAbiSignature(it) }
            .toSet() shouldBe
            setOf(
                "create()",
                "evaluate(String)",
                "evaluate(String,String)",
                "execute(byte[])",
                "compile(String,String)",
                "get(String,Class)",
                "set(String,Class,Object)",
                "close()",
            )
    }

    @Test
    fun `compatibility surface still evaluates compiles executes and binds`() {
        val bytecode = QuickJs.create().use { runtime ->
            runtime.evaluate("21 * 2") shouldBe 42
            runtime.set(
                "greeter",
                Greeter::class.java,
                object : Greeter {
                    override fun greet(name: String): String = "Hello, $name"
                },
            )
            runtime.evaluate("greeter.greet('Tsuzuki')") shouldBe "Hello, Tsuzuki"
            runtime.compile("6 * 7", "compat.js")
        }

        QuickJs.create().use { runtime ->
            runtime.execute(bytecode) shouldBe 42
            val error = assertThrows(UnsupportedOperationException::class.java) {
                runtime.get("missing", Greeter::class.java)
            }
            error.message shouldBe
                "Legacy QuickJs.get object proxies are not supported by the temporary compatibility shim"
        }
    }

    @Test
    fun `keeps extension-facing class and exception shapes`() {
        Closeable::class.java.isAssignableFrom(QuickJs::class.java) shouldBe true
        Modifier.isFinal(QuickJs::class.java.modifiers) shouldBe true
        QuickJs::class.java.name shouldBe "app.cash.quickjs.QuickJs"

        QuickJsException::class.java.name shouldBe "app.cash.quickjs.QuickJsException"
        QuickJsException::class.java.superclass shouldBe RuntimeException::class.java
        QuickJsException::class.java.declaredConstructors
            .map { constructorAbiSignature(it) }
            .toSet() shouldBe setOf("(String)", "(String,String)")
    }

    private interface Greeter {
        fun greet(name: String): String
    }

    private fun methodAbiSignature(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString(",") { abiName(it) }})"

    private fun constructorAbiSignature(constructor: Constructor<*>): String =
        "(${constructor.parameterTypes.joinToString(",") { abiName(it) }})"

    private fun abiName(type: Class<*>): String = when {
        type.isArray -> "${abiName(requireNotNull(type.componentType))}[]"
        type == String::class.java -> "String"
        type == Class::class.java -> "Class"
        type == Any::class.java -> "Object"
        else -> type.simpleName
    }
}
