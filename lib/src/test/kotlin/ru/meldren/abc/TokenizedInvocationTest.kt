package ru.meldren.abc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.meldren.abc.annotation.Command
import ru.meldren.abc.annotation.Default
import ru.meldren.abc.annotation.Sender
import ru.meldren.abc.exception.invocation.ArgumentSenderTypeMismatchException

class TokenizedInvocationTest {
    @Test
    fun preservesSpacesEmptyArgumentsAndAliases() {
        val manager = CommandManager<Any, Any>().apply { registerCommand(Echo()) }
        val args = listOf("two words", "", " tail ")
        assertEquals(args, manager.invokeTokenizedCommand<List<String>>("sender", "E", args))
        assertEquals(listOf("two", "words", "tail"), manager.invokeCommand<List<String>>("sender", "/echo two words  tail "))
    }

    @Test
    fun reportsTheExpectedSenderType() {
        var expectedType: Class<*>? = null
        val manager = CommandManager<Any, Any>().apply {
            registerCommand(Echo())
            registerExceptionHandler<ArgumentSenderTypeMismatchException> { error, _ ->
                expectedType = error.expectedType
            }
        }

        manager.invokeTokenizedCommand<List<String>>(Any(), "echo", listOf("value"))

        assertEquals(String::class.java, expectedType)
    }
}

@Command(name = "echo", aliases = ["e"])
class Echo {
    @Default
    fun echo(@Sender sender: String, vararg values: String) = values.toList()
}
