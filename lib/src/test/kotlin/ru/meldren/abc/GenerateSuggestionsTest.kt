package ru.meldren.abc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.meldren.abc.annotation.*
import ru.meldren.abc.common.CommandParameter
import ru.meldren.abc.common.SubcommandData
import ru.meldren.abc.exception.invocation.CommandPlainTextException
import ru.meldren.abc.exception.invocation.NotEnoughPermissionException
import ru.meldren.abc.processor.SuggestionProvider

class GenerateSuggestionsTest {
    private val manager = CommandManager<Any, Any>().apply {
        registerCommand(TestCommand())
        registerSuggestionProvider(Fruit::class.java) { _, input, _ ->
            Fruit.values()
                .map { it.name }
                .filter { it.startsWith(input.uppercase()) }
        }
        registerSuggestionProvider(provider = RecipientProvider)
        registerSuggestionProvider(provider = ContentProvider)
        registerCommand(FruitCommand())
        registerCommand(MessageCommand())
    }

    private fun suggest(input: String) = manager.generateSuggestions("", input).toSet()

    @Test
    fun testNonExistingCommand() {
        assertTrue(suggest("/nonexistent").isEmpty())
    }

    @Test
    fun testEmptyProviderSuggestions() {
        assertTrue(suggest("/fruit unknownPrefix").isEmpty())
    }

    @Test
    fun testEmptySuggestionsForCompleteCommand() {
        assertTrue(suggest("/test").isEmpty())
    }

    @Test
    fun testTopLevelSubcommandsSuggestions() {
        assertEquals(setOf("aaa", "sad", "sub", "subx"), suggest("/test "))
    }

    @Test
    fun testFilteredSubcommandsByPrefix() {
        assertEquals(setOf("aaa"), suggest("/test a"))
        assertEquals(setOf("sad", "sub", "subx"), suggest("/test s"))
    }

    @Test
    fun testNestedSubcommandHierarchy() {
        assertEquals(setOf("subx"), suggest("/test sub"))
        assertEquals(setOf("aab", "sup", "saint"), suggest("/test sub "))
        assertEquals(setOf("aab"), suggest("/test sub a"))
    }

    @Test
    fun testNoSuggestionsForInvalidPaths() {
        assertTrue(suggest("/test a ").isEmpty())
        assertTrue(suggest("/test a a").isEmpty())
    }

    @Test
    fun testCommandPrefixDetection() {
        assertThrows<CommandPlainTextException> { suggest("test") }
    }

    @Test
    fun testPartialCommandNameRecognition() {
        assertEquals(setOf("test"), suggest("/tes"))
    }

    @Test
    fun testCaseInsensitiveCommandHandling() {
        assertEquals(setOf("aaa"), suggest("/TEST A"))
    }

    @Test
    fun testWhitespaceNormalization() {
        assertEquals(setOf("sad", "sub", "subx"), suggest("/test    s"))
    }

    @Test
    fun testPermissionAwareSuggestions() {
        val manager = CommandManager<Any, Any>().apply {
            registerPermissionHandler { _, _ -> throw NotEnoughPermissionException("") }
            registerCommand(TestCommand())
        }
        assertTrue(manager.generateSuggestions("", "/test ").isEmpty())
    }

    @Test
    fun testRootCommandListing() {
        assertEquals(setOf("test", "fruit", "message", "m", "w", "tell"), suggest("/"))
    }

    @Test
    fun testParameterAndSubcommandSuggestionsMerging() {
        assertEquals(setOf("APPLE", "BANANA", "ORANGE", "STRAWBERRY", "aaa", "bbb", "oran"), suggest("/fruit "))
    }

    @Test
    fun testPartialInputForMergedSuggestions() {
        assertEquals(setOf("APPLE", "aaa"), suggest("/fruit a"))
    }

    @Test
    fun testTerminalSubcommandCompletion() {
        assertTrue(suggest("/fruit aaa").isEmpty())
    }

    @Test
    fun testTerminalNestedCommandSuggestions() {
        val expected = setOf("ORANGE")
        assertEquals(expected, suggest("/fruit oran"))
    }

    @Test
    fun testNestedCommandParameterSuggestions() {
        assertEquals(setOf("APPLE", "BANANA", "ORANGE", "STRAWBERRY", "sss", "bana"), suggest("/fruit oran "))
    }

    @Test
    fun testFilteredNestedParameterSuggestions() {
        assertEquals(setOf("BANANA", "bana"), suggest("/fruit oran b"))
    }

    @Test
    fun testCaseInsensitiveParameterHandling() {
        assertEquals(setOf("APPLE", "BANANA", "ORANGE", "STRAWBERRY", "aaa", "bbb", "oran"), suggest("/FRUIT "))
    }

    @Test
    fun testExcessiveWhitespaceHandling() {
        assertEquals(setOf("APPLE", "BANANA", "ORANGE", "STRAWBERRY", "aaa", "bbb", "oran"), suggest("/fruit      "))
    }

    @Test
    fun testCombinedParameterAndSubcommandSuggestions() {
        assertEquals(setOf("APPLE", "aaa"), suggest("/fruit a").toSet())
    }

    @Test
    fun testAliasMatching() {
        assertTrue(suggest("/mes").contains("message"))

        assertTrue(suggest("/te").contains("tell"))
    }

    @Test
    fun testMultiParameterDefaultMethod() {
        assertEquals(
            setOf("Alice", "Bob", "Charlie", "urgent", "u", "urg", "broadcast", "bcast", "bc"),
            suggest("/message "),
        )
        assertEquals(
            setOf("Hi", "Hello", "How are you", "Good morning"),
            suggest("/message Alice ")
        )
    }

    @Test
    fun testPermissionFilteringInNestedCommands() {
        val managerWithPermission = CommandManager<Any, Any>().apply {
            registerCommand(MessageCommand())
            registerPermissionHandler { _, commandData ->
                if (commandData.annotations.any { it is Subcommand } &&
                    (commandData as? SubcommandData)?.function?.name == "urgent") {
                    throw NotEnoughPermissionException("")
                }
            }
        }

        val suggestions = managerWithPermission.generateSuggestions("", "/message u").toSet()
        val forbiddenAliases = setOf("urgent", "u", "urg")
        for (alias in forbiddenAliases) {
            assertFalse(suggestions.contains(alias))
        }
    }
}

@Command(name = "test", aliases = [])
@Description(description = "test command")
class TestCommand {
    @Subcommand(name = "aaa", aliases = [])
    fun aaa() {
    }

    @Subcommand(name = "sad", aliases = [])
    fun sad() {
    }

    @Subcommand(name = "subx", aliases = [])
    fun subx() {
    }

    @Command(name = "sub", aliases = [])
    class sub {
        @Subcommand(name = "aab", aliases = [])
        fun aab() {
        }

        @Subcommand(name = "sup", aliases = [])
        fun sup() {
        }

        @Subcommand(name = "saint", aliases = [])
        fun saint() {
        }
    }
}

@Command(name = "fruit", aliases = [])
@Description(description = "test command")
class FruitCommand {
    @Subcommand(name = "aaa", aliases = [])
    fun aaa() {
    }

    @Subcommand(name = "bbb", aliases = [])
    fun bbb() {
    }

    @Default
    fun fruit(fruit: Fruit) {
    }

    @Command(name = "oran", aliases = [])
    class oran {
        @Default
        fun oran(fruit: Fruit) {
        }

        @Subcommand(name = "sss", aliases = [])
        fun sss() {
        }

        @Subcommand(name = "bana", aliases = [])
        fun bana() {
        }
    }
}

@Command(name = "message", aliases = ["message", "m", "w", "tell"])
class MessageCommand {

    @Default
    fun message(
        @Suggest(providerClass = RecipientProvider::class)
        recipient: String,
        @Suggest(providerClass = ContentProvider::class)
        content: String
    ) {
    }

    @Subcommand(name = "urgent", aliases = ["urgent", "u", "urg"])
    fun urgent(
        @Suggest(providerClass = RecipientProvider::class)
        recipient: String,
        @Suggest(providerClass = ContentProvider::class)
        content: String
    ) {
    }

    @Command(name = "broadcast", aliases = ["broadcast", "bcast", "bc"])
    class Broadcast {
        @Default
        fun broadcast(
            @Suggest(providerClass = RecipientProvider::class)
            recipient: String,
            @Suggest(providerClass = ContentProvider::class)
            content: String
        ) {
        }
    }
}

object RecipientProvider : SuggestionProvider<Any> {
    override fun suggest(sender: Any, input: String, parameter: CommandParameter): List<String> {
        val recipients = listOf("Alice", "Bob", "Charlie")
        return recipients.filter { it.startsWith(input, ignoreCase = true) }
    }
}

object ContentProvider : SuggestionProvider<Any> {
    override fun suggest(sender: Any, input: String, parameter: CommandParameter): List<String> {
        val contents = listOf("Hi", "Hello", "How are you", "Good morning")
        return contents.filter { it.startsWith(input, ignoreCase = true) }
    }
}

enum class Fruit {
    APPLE,
    BANANA,
    ORANGE,
    STRAWBERRY
}
