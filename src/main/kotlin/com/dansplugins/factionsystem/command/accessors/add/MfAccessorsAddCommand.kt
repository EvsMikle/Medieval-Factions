package com.dansplugins.factionsystem.command.accessors.add

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.interaction.MfInteractionStatus.ADDING_ACCESSOR
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.OfflinePlayer
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Bisected
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.conversations.ConversationContext
import org.bukkit.conversations.ConversationFactory
import org.bukkit.conversations.Prompt
import org.bukkit.conversations.ValidatingPrompt
import org.bukkit.entity.Player
import java.util.logging.Level.SEVERE

class MfAccessorsAddCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val conversationFactory = ConversationFactory(plugin)
        .withModality(true)
        .withFirstPrompt(NamePrompt())
        .withEscapeSequence(plugin.language["EscapeSequence"])
        .withLocalEcho(false)
        .thatExcludesNonPlayersWithMessage(plugin.language["CommandAccessorsAddNotAPlayer"])
        .addConversationAbandonedListener { event ->
            if (!event.gracefulExit()) {
                val conversable = event.context.forWhom
                if (conversable is Player) {
                    conversable.sendMessage(plugin.language["CommandAccessorsAddOperationCancelled"])
                }
            }
        }

    private inner class NamePrompt : ValidatingPrompt() {
        override fun getPromptText(context: ConversationContext) = plugin.language["CommandAccessorsAddNamePrompt", plugin.language["EscapeSequence"]]

        override fun isInputValid(context: ConversationContext, input: String): Boolean {
            val player = plugin.server.getOfflinePlayer(input)
            return player.isOnline || player.hasPlayedBefore()
        }

        override fun getFailedValidationText(context: ConversationContext, invalidInput: String): String? {
            return plugin.language["CommandAccessorsAddInvalidPlayer"]
        }

        override fun acceptValidatedInput(context: ConversationContext, input: String): Prompt? {
            val sender = context.forWhom as Player
            val player = plugin.server.getOfflinePlayer(input)
            val block = sender.world.getBlockAt(
                context.getSessionData("x") as Int,
                context.getSessionData("y") as Int,
                context.getSessionData("z") as Int
            )
            val blockData = block.blockData
            val holder = (block.state as? Chest)?.inventory?.holder
            val blocks = if (blockData is Bisected) {
                if (blockData.half == Bisected.Half.BOTTOM) {
                    listOf(block, block.getRelative(BlockFace.UP))
                } else {
                    listOf(block, block.getRelative(BlockFace.DOWN))
                }
            } else if (holder is DoubleChest) {
                val left = holder.leftSide as? Chest
                val right = holder.rightSide as? Chest
                listOfNotNull(left?.block, right?.block)
            } else {
                listOf(block)
            }
            addAccessor(sender, blocks, player)
            return END_OF_CONVERSATION
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.accessors.add") && !sender.hasPermission("mf.grantaccess")) {
            sender.sendMessage("$RED${plugin.language["CommandAccessorsAddNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandAccessorsAddNotAPlayer"]}")
            return true
        }
        if (args.size < 3) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(sender)
                    ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                        sender.sendMessage("$RED${plugin.language["CommandAccessorsAddFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val interactionService = plugin.services.interactionService
                interactionService.setInteractionStatus(mfPlayer.id, ADDING_ACCESSOR).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandAccessorsAddFailedToSetInteractionStatus"]}")
                    return@Runnable
                }
                sender.sendMessage("$GREEN${plugin.language["CommandAccessorsAddSelectBlock"]}")
            })
            return true
        }
        val (x, y, z) = args.take(3).map(String::toIntOrNull)
        if (x == null || y == null || z == null) {
            sender.sendMessage("$RED${plugin.language["CommandAccessorsAddUsage"]}")
            return true
        }
        if (args.size < 4) {
            val conversation = conversationFactory.buildConversation(sender)
            conversation.context.setSessionData("x", x)
            conversation.context.setSessionData("y", y)
            conversation.context.setSessionData("z", z)
            conversation.begin()
            return true
        }
        val player = plugin.server.getOfflinePlayer(args[3])
        if (!player.isOnline && !player.hasPlayedBefore()) {
            sender.sendMessage("$RED${plugin.language["CommandAccessorsAddInvalidPlayer"]}")
            return true
        }
        val block = sender.world.getBlockAt(x, y, z)
        val blockData = block.blockData
        val holder = (block.state as? Chest)?.inventory?.holder
        val blocks = if (blockData is Bisected) {
            if (blockData.half == Bisected.Half.BOTTOM) {
                listOf(block, block.getRelative(BlockFace.UP))
            } else {
                listOf(block, block.getRelative(BlockFace.DOWN))
            }
        } else if (holder is DoubleChest) {
            val left = holder.leftSide as? Chest
            val right = holder.rightSide as? Chest
            listOfNotNull(left?.block, right?.block)
        } else {
            listOf(block)
        }
        addAccessor(sender, blocks, player)
        return true
    }

    private fun addAccessor(sender: Player, blocks: List<Block>, accessor: OfflinePlayer) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val playerService = plugin.services.playerService
            val mfPlayer = playerService.getPlayer(sender)
                ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandAccessorsAddFailedToSavePlayer"]}")
                    plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
            val accessorMfPlayer = playerService.getPlayer(accessor)
                ?: playerService.save(MfPlayer(plugin, accessor)).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandAccessorsAddFailedToSavePlayer"]}")
                    plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
            val lockService = plugin.services.lockService
            val lockedBlocks = blocks.mapNotNull { lockService.getLockedBlock(MfBlockPosition.fromBukkitBlock(it)) }
            val lockedBlock = lockedBlocks.firstOrNull()
            if (lockedBlock == null) {
                sender.sendMessage("$RED${plugin.language["CommandAccessorsAddBlockNotLocked"]}")
                return@Runnable
            }
            if (lockedBlock.playerId.value != mfPlayer.id.value && lockedBlock.accessors.none { it.value == mfPlayer.id.value }) {
                sender.sendMessage("$RED${plugin.language["CommandAccessorsAddMustHaveAccess"]}")
                return@Runnable
            }
            lockService.save(lockedBlock.copy(accessors = lockedBlock.accessors + accessorMfPlayer.id)).onFailure {
                sender.sendMessage("$RED${plugin.language["CommandAccessorsAddFailedToSaveLockedBlock"]}")
                plugin.logger.log(SEVERE, "Failed to save locked block: ${it.reason.message}", it.reason.cause)
                return@Runnable
            }
            sender.sendMessage("$GREEN${plugin.language["CommandAccessorsAddSuccess", accessor.name ?: plugin.language["UnknownPlayer"]]}")
            plugin.server.scheduler.runTask(plugin, Runnable {
                sender.performCommand("accessors list ${lockedBlock.block.x} ${lockedBlock.block.y} ${lockedBlock.block.z}")
            })
        })
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ) = when {
        args.size <= 3 -> emptyList()
        args.size == 4 -> plugin.server.offlinePlayers
            .filter { it.name?.lowercase()?.startsWith(args[3].lowercase()) == true }
            .mapNotNull(OfflinePlayer::getName)
        else -> emptyList()
    }

}
