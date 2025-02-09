package ru.meldren.abc.service

import ru.meldren.abc.annotation.Cooldown
import ru.meldren.abc.annotation.Parser
import ru.meldren.abc.annotation.Sender
import ru.meldren.abc.annotation.Suggest
import ru.meldren.abc.common.AbstractCommandData
import ru.meldren.abc.common.CommandData
import ru.meldren.abc.common.SubcommandData
import ru.meldren.abc.common.findAnnotation
import ru.meldren.abc.exception.CommandException
import ru.meldren.abc.exception.invocation.*
import ru.meldren.abc.processor.ArgumentParser
import ru.meldren.abc.processor.ArgumentValidator
import ru.meldren.abc.processor.ExceptionHandler
import ru.meldren.abc.processor.SuggestionProvider
import ru.meldren.abc.util.arrayType
import ru.meldren.abc.util.checkOrThrow
import ru.meldren.abc.util.toInt
import ru.meldren.abc.util.toTypedArray
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.valueParameters

@PublishedApi
internal class CommandInvoker<S : Any, C : Any>(
    private val commandPrefix: String,
    private val processorRegistry: ProcessorRegistry<S, C>,
    private val registeredCommands: MutableSet<CommandData<C>>,
    private val defaultParsers: MutableMap<KClass<*>, ArgumentParser<*>>,
    private val parsers: MutableMap<KClass<out ArgumentParser<*>>, ArgumentParser<*>>,
    private val validators: MutableMap<KClass<out Annotation>, ArgumentValidator<*, *, S>>,
    private val handlers: MutableMap<KClass<out CommandInvocationException>, ExceptionHandler<*, *>>,
    private val defaultSuggestions: MutableMap<KClass<*>, SuggestionProvider<S>>,
    private val suggestions: MutableMap<KClass<out SuggestionProvider<S>>, SuggestionProvider<S>>,
    private val commandsByAliases: MutableMap<String, CommandData<C>>
) {

    @Suppress("UNCHECKED_CAST")
    fun <T> invokeCommand(sender: S, input: String): T? {
        return try {
            val args = parseInput(input)
            val rawArgs = args.toList()
            val commandData = findCommand(args, rawArgs)
            val subcommandsData = findSubcommands(args, rawArgs, commandData)
            val (subcommandData, params) = findSubcommand(sender, args, subcommandsData)

            processorRegistry.permissionHandler?.let {
                it.checkPermission(sender, commandData)
                it.checkPermission(sender, subcommandData)
            }

            if (!checkBeforeCommands(sender, commandData)) {
                throw BeforeCommandFailException(rawArgs, commandData, subcommandData)
            }

            val cooldown = checkCooldown(sender, commandData)
            if (cooldown > 0) {
                throw CommandCooldownException(cooldown)
            }

            callSubcommand(commandData, subcommandData, params) as T?
        } catch (ex: Exception) {
            val cause = (ex.cause as? CommandInvocationException) ?: ex
            if (cause !is CommandInvocationException) {
                throw ex
            }
            (handlers[cause::class] as? ExceptionHandler<CommandInvocationException, S>)?.handle(cause, sender)
            null
        }
    }

    fun generateSuggestions(sender: S, input: String): List<String> {
        if (!input.startsWith(commandPrefix)) throw CommandPlainTextException()

        val completeInput = input.endsWith(" ")
        val withoutPrefix = input.substring(commandPrefix.length)
        val rawTokens = withoutPrefix.split("\\s+".toRegex()).filter { it.isNotEmpty() }.toMutableList()
        if (completeInput) rawTokens += ""

        if (rawTokens.isEmpty()) {
            return registeredCommands.filter { hasPermission(sender, it) }
                .flatMap { it.aliases }
                .distinct()
        }

        val commandToken = rawTokens.removeAt(0)
        val rootCmd = commandsByAliases[commandToken.lowercase()] ?: run {
            return registeredCommands.filter { hasPermission(sender, it) }
                .flatMap { it.aliases.filter { alias -> alias.startsWith(commandToken, ignoreCase = true) } }
                .distinct()
        }
        if (!hasPermission(sender, rootCmd)) return emptyList()

        val (completeArgs, pendingToken) = if (rawTokens.isEmpty()) {
            return emptyList()
        } else if (completeInput) {
            Pair(rawTokens.dropLast(1), "")
        } else {
            Pair(rawTokens.dropLast(1), rawTokens.last())
        }

        var resolvedTokens = 0
        var currentCommand = rootCmd
        var methodSubcommandChosen = false

        for (token in completeArgs) {
            val child = currentCommand.childByAliases[token.lowercase()]
            if (child != null && hasPermission(sender, child)) {
                currentCommand = child
                resolvedTokens++
                continue
            }
            if (currentCommand.subcommandByAliases.containsKey(token.lowercase())) {
                methodSubcommandChosen = true
                resolvedTokens++
                continue
            }
            if (currentCommand.defaultSubcommands.isNotEmpty()) {
                break
            } else {
                return emptyList()
            }
        }

        if (methodSubcommandChosen && pendingToken.isEmpty()) return emptyList()

        val paramIndex = completeArgs.size - resolvedTokens

        val suggestionsSet = mutableSetOf<String>()

        for (defaultSub in currentCommand.defaultSubcommands) {
            if (!hasPermission(sender, defaultSub)) continue
            if (paramIndex < defaultSub.parameters.size) {
                val param = defaultSub.parameters[paramIndex]
                val provider = this.suggestions[param.findAnnotation<Suggest>()?.providerClass]
                    ?: defaultSuggestions[param.type.kotlin]
                if (provider != null) {
                    val paramSuggestions = provider.suggest(sender, pendingToken, param)
                    val filtered = if (pendingToken.isNotEmpty())
                        paramSuggestions.filter {
                            it.startsWith(pendingToken, ignoreCase = true) && !it.equals(
                                pendingToken,
                                ignoreCase = true
                            )
                        }
                    else paramSuggestions
                    suggestionsSet += filtered
                }
            }
        }

        if (paramIndex == 0) {
            for ((alias, subList) in currentCommand.subcommandByAliases) {
                if (alias.startsWith(pendingToken, ignoreCase = true) && !alias.equals(
                        pendingToken,
                        ignoreCase = true
                    )
                ) {
                    if (subList.any { hasPermission(sender, it) }) {
                        suggestionsSet += alias
                    }
                }
            }
            for ((alias, childCmd) in currentCommand.childByAliases) {
                if (alias.startsWith(pendingToken, ignoreCase = true) &&
                    !alias.equals(pendingToken, ignoreCase = true) &&
                    hasPermission(sender, childCmd)
                ) {
                    suggestionsSet += alias
                }
            }
        }

        return suggestionsSet.toList().distinct()
    }

    private fun hasPermission(sender: S, commandData: AbstractCommandData) = try {
        processorRegistry.permissionHandler?.checkPermission(sender, commandData)
        true
    } catch (_: NotEnoughPermissionException) {
        false
    }

    private fun parseInput(input: String): MutableList<String> {
        val handledInput = input.trim()
        if (!handledInput.startsWith(commandPrefix)) {
            throw CommandPlainTextException()
        }
        return LinkedList(
            handledInput
                .removePrefix(commandPrefix)
                .split("\\s+".toRegex())
        )
    }

    private fun findCommand(args: MutableList<String>, rawArgs: List<String>): CommandData<C> {
        var command = commandsByAliases[args.getOrNull(0)?.lowercase()] ?: throw CommandNotFoundException(rawArgs)
        var i = 0
        for (j in 1 until args.size) {
            val alias = args[j].lowercase()
            command.childByAliases[alias]?.let {
                command = it
                i = j
            } ?: break
        }
        repeat(i + 1) { args.removeFirst() }
        return command
    }

    private fun findAllSubcommands(
        args: MutableList<String>,
        commandData: CommandData<C>
    ): List<SubcommandData> {
        val alias = args.getOrNull(0)?.lowercase()
        var subcommands = commandData.subcommandByAliases[alias]
        if (subcommands == null) {
            subcommands = commandData.defaultSubcommands.toMutableList()
        } else {
            args.removeFirst()
        }
        return subcommands
    }

    private fun findSubcommands(
        args: MutableList<String>,
        rawArgs: List<String>,
        commandData: CommandData<C>
    ): List<SubcommandData> {
        val subcommands = findAllSubcommands(args, commandData)

        if (subcommands.isEmpty()) {
            throw SubcommandNotFoundException(rawArgs, commandData)
        }

        val incorrectSubcommands = subcommands.filter { subcommand ->
            val params = subcommand.function.valueParameters

            val hasSender = params.any { it.hasAnnotation<Sender>() }
            val hasVararg = params.lastOrNull()?.isVararg ?: false

            val argsNumber = args.size
            val requiredArgsNumber = params.size - hasSender.toInt()
            argsNumber != requiredArgsNumber && !(hasVararg && argsNumber >= requiredArgsNumber)
        }

        if (subcommands.size == incorrectSubcommands.size) {
            throw IncorrectArgumentCountException(rawArgs, commandData, incorrectSubcommands)
        }

        return subcommands - incorrectSubcommands.toSet()
    }

    private fun findSubcommand(
        sender: S,
        args: MutableList<String>,
        subcommandsData: List<SubcommandData>
    ): Pair<SubcommandData, Array<Any>> {
        lateinit var exception: ArgumentParseException
        for (i in subcommandsData.indices) {
            try {
                val subcommandData = subcommandsData[i]
                val params = parseSubcommandArguments(sender, args, subcommandData)
                return subcommandData to params.map { it.first }.toTypedArray()
            } catch (ex: ArgumentParseException) {
                exception = ex
            }
        }
        throw exception
    }

    private fun parseSubcommandArguments(
        sender: S,
        args: MutableList<String>,
        subcommandData: SubcommandData
    ): MutableList<Pair<Any, List<Annotation>>> {
        val params: MutableList<Pair<Any, List<Annotation>>> = args
            .map { it to emptyList<Annotation>() }.toMutableList()

        val functionParams = subcommandData.function.valueParameters

        addSenderArgToParams(params, functionParams, sender)

        val hasVararg = functionParams.lastOrNull()?.isVararg ?: false
        val commonArgsNumber = functionParams.size - hasVararg.toInt()
        addCommonArgsToParams(params, functionParams, commonArgsNumber)

        validateParams(sender, params)

        if (hasVararg) {
            addVarargToParams(sender, params, functionParams)
        }

        return params
    }

    private fun validateParams(sender: S, params: List<Pair<Any, List<Annotation>>>) {
        params.forEach { (param, annotations) ->
            annotations.forEach annotation@{ annotation ->
                val validator = validators[annotation.annotationClass] ?: return@annotation
                try {
                    @Suppress("UNCHECKED_CAST")
                    (validator as ArgumentValidator<Any, Annotation, S>).validate(sender, param, annotation)
                } catch (_: ClassCastException) {
                    throw CommandException("@${annotation.annotationClass.simpleName} validator is not compatible with ${param::class.simpleName}.")
                }
            }
        }
    }

    private fun addSenderArgToParams(
        params: MutableList<Pair<Any, List<Annotation>>>,
        functionParams: List<KParameter>,
        sender: S
    ) {
        val senderArgIndex = functionParams.indexOfFirst { it.hasAnnotation<Sender>() }
        if (senderArgIndex != -1) {
            val param = functionParams[senderArgIndex]
            if (!(param.type.classifier as KClass<*>).isSuperclassOf(sender::class)) {
                throw ArgumentSenderTypeMismatchException()
            }
            val paramData = sender to param.annotations
            params.add(senderArgIndex, paramData)
        }
    }

    private fun addCommonArgsToParams(
        params: MutableList<Pair<Any, List<Annotation>>>,
        functionParams: List<KParameter>,
        number: Int
    ) {
        for (index in 0 until number) {
            val parameter = functionParams[index]
            if (parameter.hasAnnotation<Sender>()) {
                continue
            }
            val parser = getParser(parameter)
            val param = params[index].first.toString()
            val parsedParam = parser?.parse(param) ?: param
            val paramData = parsedParam to parameter.annotations
            params[index] = paramData
        }
    }

    private fun addVarargToParams(
        sender: S,
        params: MutableList<Pair<Any, List<Annotation>>>,
        functionParams: List<KParameter>
    ) {
        val varargParam = functionParams.last()
        val arrayType = varargParam.arrayType()!!
        val parser = getParser(varargParam, arrayType)

        val array = mutableListOf<Any>()
        for (index in functionParams.size - 1 until params.size) {
            val param = params[index].first.toString()
            val parsedParam = parser?.parse(param) ?: param
            array.add(parsedParam)
        }

        validateParams(sender, array.map { it to varargParam.annotations })

        val sizeOfVarargs = params.size - functionParams.size + 1
        repeat(sizeOfVarargs) { params.removeLast() }

        val paramData = array.toTypedArray(arrayType) to varargParam.annotations
        params.add(paramData)
    }

    private fun getParser(parameter: KParameter, type: KClassifier? = parameter.type.classifier): ArgumentParser<*>? {
        val annotation = parameter.findAnnotation<Parser>()
        return if (annotation == null) {
            if (type == String::class && type !in defaultParsers) {
                return null
            }
            checkOrThrow(type in defaultParsers) {
                type as KClass<*>
                CommandException("Default ${type.simpleName} parser is not registered.")
            }
            defaultParsers[type]
        } else {
            checkOrThrow(annotation.parserClass in parsers) {
                CommandException("Parser ${annotation.parserClass.simpleName} must be registered before using.")
            }
            parsers[annotation.parserClass]
        }
    }

    private fun callSubcommand(commandData: CommandData<C>, subcommandData: SubcommandData, params: Array<Any>): Any? {
        return try {
            subcommandData.function.call(commandData.instance, *params)
        } catch (ex: ClassCastException) {
            throw CommandException("Requested return type is not compatible with function ${subcommandData.function.name} in ${commandData.instance::class.simpleName}.")
        }
    }

    private fun checkBeforeCommands(sender: S, commandData: CommandData<C>): Boolean {
        return commandData.beforeCommands.all {
            val params = mutableListOf<Any>(commandData.instance)
            if (it.valueParameters.isNotEmpty()) {
                params.add(sender)
            }
            it.call(*params.toTypedArray()) as Boolean
        }
    }

    private fun checkCooldown(sender: S, commandData: CommandData<C>): Long {
        val annotation = commandData.instance::class.findAnnotation<Cooldown>() ?: return 0
        val left = processorRegistry.cooldownHandler?.test(sender, commandData, annotation)
        return left ?: 0
    }
}